// AddInclineFitGem.java — Java 21  •  Garmin FIT SDK 21.117
// ==============================================================
// • Inject synthetic GPS + Variable incline into treadmill FITs
// • Analyse outdoor FITs (Enhanced for GAP fields & Lap Summary)
//
// Build :  javac -cp fit.jar AddInclineFitGem.java
// Run   :  java  -cp .:fit.jar AddInclineFitGem …
//
// --------------------------------------------------------------
// ❶ Inject  (default)
//    java  -cp .:fit.jar AddInclineFit  in.fit  out.fit  lat lon [alt] [bearing] [--virtual]
//
// ❷ Analyse
//    java  -cp .:fit.jar AddInclineFit --analyse  file.fit
// --------------------------------------------------------------

import com.garmin.fit.*; // Main FIT SDK classes
import java.io.*;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;

public class AddInclineFitGem { // Ensure filename is AddInclineFitGem.java

    // Pair class for grouping developer fields (Dev Index, Field Num)
    private record Pair<L, R>(L left, R right) {}

    // ---------- constants ----------
    private static final double SEMI_PER_DEG       = (1L << 31) / 180.0;
    private static final double METERS_PER_DEG_LAT = 111_320.0;
    private static final double GRADE              = 0.10; // Target average incline grade
    private static final double GEO_STEP           = 3.0;  // Distance between injected GPS points
    // ** NEW: Factor to control altitude noise/variability **
    // Adjust this value to make the grade more or less variable (e.g., 0.2 means +/- 0.1m noise)
    private static final double ALTITUDE_NOISE_FACTOR = 0.0;


    // ---------- helpers ----------
    private static int    toSemi(double deg)  { return (int)Math.round(deg * SEMI_PER_DEG); }
    private static double semiToDeg(int semi) { return semi / SEMI_PER_DEG; }
    private static double m2degLat(double m)  { return m / METERS_PER_DEG_LAT; }
    private static double m2degLon(double m,double latDeg){ return m / (METERS_PER_DEG_LAT * Math.cos(Math.toRadians(latDeg))); }
    private static double pos(Integer s){ return s==null?Double.NaN:semiToDeg(s); }
    private static boolean hasValue(Mesg msg, String fieldName) { if (msg == null) return false; com.garmin.fit.Field f = msg.getField(fieldName); return f != null && f.getNumValues() > 0 && f.getValue(0) != null; }
    private static boolean hasValue(Mesg msg, int fieldNum) { if (msg == null) return false; com.garmin.fit.Field f = msg.getField(fieldNum); return f != null && f.getNumValues() > 0 && f.getValue(0) != null; }
    private static String present(boolean value) { return value ? "Present" : "Absent "; }

    // Helper to safely remove a field by its number
    private static void safeRemoveField(Mesg msg, int fieldNum) {
        if (msg == null) return;
        com.garmin.fit.Field field = msg.getField(fieldNum);
        if (field != null) {
            msg.removeField(field);
        }
    }


    // ===========================================================
    public static void main(String[] args) throws IOException {
        if (args.length < 1) { System.err.println(/* Usage */); System.exit(1); }
        if ("--analyse".equals(args[0])) { if (args.length < 2) { System.err.println("Analyse requires file path"); System.exit(1); } analyse(args[1]); return; }
        boolean virtual = false; if (args.length > 1 && args[args.length-1].equalsIgnoreCase("--virtual")) { virtual = true; args = java.util.Arrays.copyOf(args, args.length-1); }
        if (args.length < 5) { System.err.println("Inject requires args: in.fit out.fit lat lon [alt] [bearing] [--virtual]"); System.exit(1); }
        inject(args, virtual);
    }


    // ===========================================================
    //  Analyse mode (Unchanged from previous version)
    // ===========================================================
    private static void analyse(String inFile) throws IOException {
        System.out.println("Analysing file: " + inFile);
        List<Mesg> msgs = decodeAll(inFile);
        if (msgs.isEmpty()) { System.out.println("No messages decoded."); return; }

        // --- Data Structures ---
        Map<Integer, Long> msgCounts = new TreeMap<>();
        Map<Integer, String> mesgNumToNameMap = new HashMap<>();
        List<RecordMesg> recordMsgs = new ArrayList<>();
        List<LapMesg> lapMsgs = new ArrayList<>();
        List<EventMesg> eventMsgs = new ArrayList<>();
        List<DeveloperField> developerFields = new ArrayList<>();
        Set<String> subSportSources = new HashSet<>();
        FileIdMesg  fileId  = null; SportMesg   sportM  = null; SessionMesg sessM   = null;
        ActivityMesg activityM = null;

        // --- First Pass ---
        for (Mesg m : msgs) {
            msgCounts.merge(m.getNum(), 1L, Long::sum);
            mesgNumToNameMap.putIfAbsent(m.getNum(), m.getName());
            switch (m.getNum()) {
                case MesgNum.FILE_ID   -> fileId = new FileIdMesg(m);
                case MesgNum.SPORT     -> sportM = new SportMesg(m);
                case MesgNum.SESSION   -> sessM  = new SessionMesg(m);
                case MesgNum.LAP       -> lapMsgs.add(new LapMesg(m));
                case MesgNum.RECORD    -> recordMsgs.add(new RecordMesg(m));
                case MesgNum.EVENT     -> eventMsgs.add(new EventMesg(m));
                case MesgNum.ACTIVITY  -> activityM = new ActivityMesg(m);
            }
            if (m.getField("sub_sport") != null) { subSportSources.add(m.getName()); }
            Iterable<DeveloperField> devFieldsIterable = m.getDeveloperFields();
            if (devFieldsIterable != null) { for (DeveloperField df : devFieldsIterable) { if (df != null) { developerFields.add(df); } } }
        }

        // --- Basic Info & Message Counts ---
        System.out.printf("Total messages        : %d%n", msgs.size());
        System.out.println("── Message Type Counts ─────────────────────────────────────");
        msgCounts.forEach((num, count) -> {
             String msgName = mesgNumToNameMap.getOrDefault(num, "Unknown_" + num);
             System.out.printf("  %-20s (%3d): %d%n", msgName, num, count);
         });
        String fType = (fileId!=null && fileId.getType()!=null) ? fileId.getType().toString() : "?";
        String mfg   = (fileId!=null && fileId.getManufacturer() != null) ? Manufacturer.getStringFromValue(fileId.getManufacturer()) : "?";
        String prod  = (fileId!=null && fileId.getProduct() != null) ? String.valueOf(fileId.getProduct()) : "?";
        System.out.printf("File Type             : %s%n", fType);
        System.out.printf("Manufacturer/Product  : %s / %s%n", mfg, prod);

        // --- Sport / SubSport Info ---
        Sport    sport   = (sportM != null) ? sportM.getSport() : (sessM != null ? sessM.getSport() : null);
        SubSport subSp   = (sportM != null) ? sportM.getSubSport() : (sessM != null ? sessM.getSubSport() : null);
        String   profileName = (sportM != null && sportM.getName() != null) ? sportM.getName() : "";
        if (profileName.isEmpty() && sessM != null && sessM.getSportProfileName() != null) { profileName = sessM.getSportProfileName(); }
        System.out.printf("Primary Sport         : %s%n", (sport != null) ? sport.toString() : "?");
        System.out.printf("Primary SubSport      : %s%n", (subSp != null) ? subSp.toString() : "?");
        System.out.printf("Profile Name          : %s%n", profileName.isEmpty() ? "(Not Set)" : profileName);
        System.out.printf("Messages with SubSport: %s%n", subSportSources.isEmpty() ? "None" : String.join(", ", subSportSources));

        // --- Activity Message Info ---
        System.out.println("── ACTIVITY Message Analysis ──────────────────────────────");
        if (activityM != null) {
             System.out.printf("  Timestamp : %s%n", activityM.getTimestamp());
             System.out.printf("  Event     : %s%n", activityM.getEvent());
             System.out.printf("  EventType : %s%n", activityM.getEventType());
        } else {
             System.out.println("  (No ACTIVITY message found)");
        }

        // --- Record Message Analysis ---
        int recCount = recordMsgs.size();
        long recWithGps = recordMsgs.stream().filter(r -> r.getPositionLat() != null && r.getPositionLong() != null).count();
        long recWithDist = recordMsgs.stream().filter(r -> r.getDistance() != null).count();
        long recWithAlt = recordMsgs.stream().filter(r -> r.getAltitude() != null).count();
        long recWithEnhAlt = recordMsgs.stream().filter(r -> r.getEnhancedAltitude() != null).count();
        long recWithSpeed = recordMsgs.stream().filter(r -> r.getSpeed() != null).count();
        long recWithEnhSpeed = recordMsgs.stream().filter(r -> r.getEnhancedSpeed() != null).count();
        long recWithGrade = recordMsgs.stream().filter(r -> r.getGrade() != null).count();
        long recWithVertRatio = recordMsgs.stream().filter(r -> r.getVerticalRatio() != null).count();
        System.out.println("── RECORD Message Analysis ────────────────────────────────");
        System.out.printf("Total Records         : %d%n", recCount);
        if (recCount > 0) { /* ... print stats ... */
             System.out.printf("  with GPS (Lat/Lon)  : %d (%.1f%%)%n", recWithGps, 100.0 * recWithGps / recCount);
             System.out.printf("  with Distance       : %d (%.1f%%)%n", recWithDist, 100.0 * recWithDist / recCount);
             System.out.printf("  with Altitude       : %d (%.1f%%)%n", recWithAlt, 100.0 * recWithAlt / recCount);
             System.out.printf("  with Enh. Altitude  : %d (%.1f%%)%n", recWithEnhAlt, 100.0 * recWithEnhAlt / recCount);
             System.out.printf("  with Speed          : %d (%.1f%%)%n", recWithSpeed, 100.0 * recWithSpeed / recCount);
             System.out.printf("  with Enh. Speed     : %d (%.1f%%)%n", recWithEnhSpeed, 100.0 * recWithEnhSpeed / recCount);
             System.out.printf("  with Grade          : %d (%.1f%%)%n", recWithGrade, 100.0 * recWithGrade / recCount);
             System.out.printf("  with Vertical Ratio : %d (%.1f%%)%n", recWithVertRatio, 100.0 * recWithVertRatio / recCount);
        }

        // --- Session Message Analysis ---
        System.out.println("── SESSION Message Analysis ───────────────────────────────");
        if (sessM != null) { /* ... print stats ... */
             System.out.printf("Start Pos (Lat/Lon) : %s / %s%n", present(hasValue(sessM, SessionMesg.StartPositionLatFieldNum)), present(hasValue(sessM, SessionMesg.StartPositionLongFieldNum)));
             System.out.printf("End Pos (Lat/Lon)   : %s / %s%n", present(hasValue(sessM, SessionMesg.EndPositionLatFieldNum)), present(hasValue(sessM, SessionMesg.EndPositionLongFieldNum)));
             System.out.printf("Total Distance      : %s (%.2f m)%n", present(hasValue(sessM, SessionMesg.TotalDistanceFieldNum)), sessM.getTotalDistance());
             System.out.printf("Total Ascent        : %s (%d m)%n", present(hasValue(sessM, SessionMesg.TotalAscentFieldNum)), sessM.getTotalAscent());
             System.out.printf("Total Descent       : %s (%d m)%n", present(hasValue(sessM, SessionMesg.TotalDescentFieldNum)), sessM.getTotalDescent());
             System.out.printf("Avg Speed           : %s%n", present(hasValue(sessM, SessionMesg.AvgSpeedFieldNum)));
             System.out.printf("Max Speed           : %s%n", present(hasValue(sessM, SessionMesg.MaxSpeedFieldNum)));
             System.out.printf("Enh Avg Speed       : %s%n", present(hasValue(sessM, SessionMesg.EnhancedAvgSpeedFieldNum)));
             System.out.printf("Enh Max Speed       : %s%n", present(hasValue(sessM, SessionMesg.EnhancedMaxSpeedFieldNum)));
             System.out.printf("Min Altitude        : %s%n", present(hasValue(sessM, SessionMesg.MinAltitudeFieldNum)));
             System.out.printf("Max Altitude        : %s%n", present(hasValue(sessM, SessionMesg.MaxAltitudeFieldNum)));
             System.out.printf("Enh Min Altitude    : %s%n", present(hasValue(sessM, SessionMesg.EnhancedMinAltitudeFieldNum)));
             System.out.printf("Enh Max Altitude    : %s%n", present(hasValue(sessM, SessionMesg.EnhancedMaxAltitudeFieldNum)));
             System.out.printf("Total Frac Ascent   : %s%n", present(hasValue(sessM, SessionMesg.TotalFractionalAscentFieldNum)));
             System.out.printf("Total Frac Descent  : %s%n", present(hasValue(sessM, SessionMesg.TotalFractionalDescentFieldNum)));
             System.out.printf("Avg Grade           : %s%n", present(hasValue(sessM, SessionMesg.AvgGradeFieldNum)));
             System.out.printf("Avg Vertical Ratio  : %s%n", present(hasValue(sessM, SessionMesg.AvgVerticalRatioFieldNum)));
        } else { System.out.println("  (No SESSION message found)"); }

        // --- Lap Message Analysis ---
         System.out.println("── LAP Message Analysis ───────────────────────────────────");
         int lapCount = lapMsgs.size();
         if (lapCount > 0) {
             System.out.printf("Total Laps          : %d%n", lapCount);
             System.out.println("--- Per-Lap Details & Checks ---");
             int sumLapAscent = 0; int sumLapDescent = 0;
             boolean anyLapHasAvgSpeed = false, anyLapHasMaxSpeed = false, anyLapHasEnhAvgSpeed = false;
             boolean anyLapHasEnhMaxSpeed = false, anyLapHasMinAlt = false, anyLapHasMaxAlt = false;
             boolean anyLapHasEnhMinAlt = false, anyLapHasEnhMaxAlt = false, anyLapHasFracAsc = false;
             boolean anyLapHasFracDesc = false, anyLapHasAvgGrade = false, anyLapHasAvgVertRatio = false;

             for (int i = 0; i < lapMsgs.size(); i++) {
                 LapMesg l = lapMsgs.get(i);
                 Integer lapIndex = l.getMessageIndex() != null ? l.getMessageIndex() : i;
                 Integer ascent = l.getTotalAscent(); Integer descent = l.getTotalDescent();
                 Float grade = l.getAvgGrade(); Float vertRatio = l.getAvgVerticalRatio();
                 sumLapAscent += (ascent != null ? ascent : 0); sumLapDescent += (descent != null ? descent : 0);
                 System.out.printf("  Lap %2d: Ascent=%-5s Descent=%-5s AvgGrade=%-6s AvgVertRatio=%-6s%n",
                     lapIndex, ascent != null ? ascent.toString() : "N/A", descent != null ? descent.toString() : "N/A",
                     grade != null ? String.format("%.2f%%", grade) : "N/A", vertRatio != null ? String.format("%.2f", vertRatio) : "N/A");

                 boolean curLapHasAvgSpeed = hasValue(l, LapMesg.AvgSpeedFieldNum);
                 boolean curLapHasMaxSpeed = hasValue(l, LapMesg.MaxSpeedFieldNum);
                 boolean curLapHasEnhAvgSpeed = hasValue(l, LapMesg.EnhancedAvgSpeedFieldNum);
                 boolean curLapHasEnhMaxSpeed = hasValue(l, LapMesg.EnhancedMaxSpeedFieldNum);
                 boolean curLapHasMinAlt = hasValue(l, LapMesg.MinAltitudeFieldNum);
                 boolean curLapHasMaxAlt = hasValue(l, LapMesg.MaxAltitudeFieldNum);
                 boolean curLapHasEnhMinAlt = hasValue(l, LapMesg.EnhancedMinAltitudeFieldNum);
                 boolean curLapHasEnhMaxAlt = hasValue(l, LapMesg.EnhancedMaxAltitudeFieldNum);
                 boolean curLapHasFracAsc = hasValue(l, LapMesg.TotalFractionalAscentFieldNum);
                 boolean curLapHasFracDesc = hasValue(l, LapMesg.TotalFractionalDescentFieldNum);
                 boolean curLapHasAvgGrade = hasValue(l, LapMesg.AvgGradeFieldNum);
                 boolean curLapHasAvgVertRatio = hasValue(l, LapMesg.AvgVerticalRatioFieldNum);

                 if (!curLapHasEnhAvgSpeed) System.out.println("    USER_ALERT:: Lap " + lapIndex + " is missing Enhanced Avg Speed!");
                 if (!curLapHasEnhMaxSpeed) System.out.println("    USER_ALERT:: Lap " + lapIndex + " is missing Enhanced Max Speed!");
                 if (!curLapHasEnhMinAlt) System.out.println("    USER_ALERT:: Lap " + lapIndex + " is missing Enhanced Min Altitude!");
                 if (!curLapHasEnhMaxAlt) System.out.println("    USER_ALERT:: Lap " + lapIndex + " is missing Enhanced Max Altitude!");
                 if (!curLapHasFracAsc) System.out.println("    USER_ALERT:: Lap " + lapIndex + " is missing Total Fractional Ascent!");
                 if (!curLapHasFracDesc) System.out.println("    USER_ALERT:: Lap " + lapIndex + " is missing Total Fractional Descent!");

                 anyLapHasAvgSpeed |= curLapHasAvgSpeed; anyLapHasMaxSpeed |= curLapHasMaxSpeed;
                 anyLapHasEnhAvgSpeed |= curLapHasEnhAvgSpeed; anyLapHasEnhMaxSpeed |= curLapHasEnhMaxSpeed;
                 anyLapHasMinAlt |= curLapHasMinAlt; anyLapHasMaxAlt |= curLapHasMaxAlt;
                 anyLapHasEnhMinAlt |= curLapHasEnhMinAlt; anyLapHasEnhMaxAlt |= curLapHasEnhMaxAlt;
                 anyLapHasFracAsc |= curLapHasFracAsc; anyLapHasFracDesc |= curLapHasFracDesc;
                 anyLapHasAvgGrade |= curLapHasAvgGrade; anyLapHasAvgVertRatio |= curLapHasAvgVertRatio;
             }
             System.out.println("--- Summary Presence (Across All Laps) ---");
             System.out.printf("Sum of Lap Ascent   : %d m%n", sumLapAscent);
             System.out.printf("Sum of Lap Descent  : %d m%n", sumLapDescent);
             System.out.printf("Avg Speed           : %s%n", present(anyLapHasAvgSpeed));
             System.out.printf("Max Speed           : %s%n", present(anyLapHasMaxSpeed));
             System.out.printf("Enh Avg Speed       : %s%n", present(anyLapHasEnhAvgSpeed));
             System.out.printf("Enh Max Speed       : %s%n", present(anyLapHasEnhMaxSpeed));
             System.out.printf("Min Altitude        : %s%n", present(anyLapHasMinAlt));
             System.out.printf("Max Altitude        : %s%n", present(anyLapHasMaxAlt));
             System.out.printf("Enh Min Altitude    : %s%n", present(anyLapHasEnhMinAlt));
             System.out.printf("Enh Max Altitude    : %s%n", present(anyLapHasEnhMaxAlt));
             System.out.printf("Total Frac Ascent   : %s%n", present(anyLapHasFracAsc));
             System.out.printf("Total Frac Descent  : %s%n", present(anyLapHasFracDesc));
             System.out.printf("Avg Grade           : %s%n", present(anyLapHasAvgGrade));
             System.out.printf("Avg Vertical Ratio  : %s%n", present(anyLapHasAvgVertRatio));
         } else { System.out.println("  (No LAP messages found)"); }

        // --- Consolidated GAP Readiness Check ---
        System.out.println("── GAP Field Check Summary ────────────────────────────────");
        String pctFmt = "%d (%.0f%%)";
        System.out.println("  Source   | Field              | Presence / Count (%)");
        System.out.println("  ---------|--------------------|-----------------------");
        if (recCount > 0) { /* ... print record stats ... */
             System.out.printf("  RECORD   | position_lat/long  | " + pctFmt + "%n", recWithGps, 100.0 * recWithGps / recCount);
             System.out.printf("  RECORD   | distance           | " + pctFmt + "%n", recWithDist, 100.0 * recWithDist / recCount);
             System.out.printf("  RECORD   | altitude (legacy)  | " + pctFmt + "%n", recWithAlt, 100.0 * recWithAlt / recCount);
             System.out.printf("  RECORD   | enhanced_altitude  | " + pctFmt + "%n", recWithEnhAlt, 100.0 * recWithEnhAlt / recCount);
             System.out.printf("  RECORD   | speed (legacy)     | " + pctFmt + "%n", recWithSpeed, 100.0 * recWithSpeed / recCount);
             System.out.printf("  RECORD   | enhanced_speed     | " + pctFmt + "%n", recWithEnhSpeed, 100.0 * recWithEnhSpeed / recCount);
             System.out.printf("  RECORD   | grade              | " + pctFmt + "%n", recWithGrade, 100.0 * recWithGrade / recCount);
             System.out.printf("  RECORD   | vertical_ratio     | " + pctFmt + "%n", recWithVertRatio, 100.0 * recWithVertRatio / recCount);
        } else { System.out.println("  RECORD   | (No Records)       | N/A"); }
        System.out.println("  ---------|--------------------|-----------------------");
        if (sessM != null) { /* ... print session stats ... */
             System.out.printf("  SESSION  | altitude (legacy)  | Min: %s, Max: %s%n", present(hasValue(sessM, SessionMesg.MinAltitudeFieldNum)), present(hasValue(sessM, SessionMesg.MaxAltitudeFieldNum)));
             System.out.printf("  SESSION  | enhanced_altitude  | Min: %s, Max: %s%n", present(hasValue(sessM, SessionMesg.EnhancedMinAltitudeFieldNum)), present(hasValue(sessM, SessionMesg.EnhancedMaxAltitudeFieldNum)));
             System.out.printf("  SESSION  | speed (legacy)     | Avg: %s, Max: %s%n", present(hasValue(sessM, SessionMesg.AvgSpeedFieldNum)), present(hasValue(sessM, SessionMesg.MaxSpeedFieldNum)));
             System.out.printf("  SESSION  | enhanced_speed     | Avg: %s, Max: %s%n", present(hasValue(sessM, SessionMesg.EnhancedAvgSpeedFieldNum)), present(hasValue(sessM, SessionMesg.EnhancedMaxSpeedFieldNum)));
             System.out.printf("  SESSION  | frac_ascent/descent| %s / %s%n", present(hasValue(sessM, SessionMesg.TotalFractionalAscentFieldNum)), present(hasValue(sessM, SessionMesg.TotalFractionalDescentFieldNum)));
             System.out.printf("  SESSION  | grade              | Avg: %s%n", present(hasValue(sessM, SessionMesg.AvgGradeFieldNum)));
             System.out.printf("  SESSION  | vertical_ratio     | Avg: %s%n", present(hasValue(sessM, SessionMesg.AvgVerticalRatioFieldNum)));
        } else { System.out.println("  SESSION  | (No Session Msg)   | N/A"); }
         System.out.println("  ---------|--------------------|-----------------------");
         if (lapCount > 0) { /* ... print lap stats ... */
             System.out.printf("  LAP (Any)| altitude (legacy)  | Min: %s, Max: %s%n", present(lapMsgs.stream().anyMatch(l->hasValue(l, LapMesg.MinAltitudeFieldNum))), present(lapMsgs.stream().anyMatch(l->hasValue(l, LapMesg.MaxAltitudeFieldNum))));
             System.out.printf("  LAP (Any)| enhanced_altitude  | Min: %s, Max: %s%n", present(lapMsgs.stream().anyMatch(l->hasValue(l, LapMesg.EnhancedMinAltitudeFieldNum))), present(lapMsgs.stream().anyMatch(l->hasValue(l, LapMesg.EnhancedMaxAltitudeFieldNum))));
             System.out.printf("  LAP (Any)| speed (legacy)     | Avg: %s, Max: %s%n", present(lapMsgs.stream().anyMatch(l->hasValue(l, LapMesg.AvgSpeedFieldNum))), present(lapMsgs.stream().anyMatch(l->hasValue(l, LapMesg.MaxSpeedFieldNum))));
             System.out.printf("  LAP (Any)| enhanced_speed     | Avg: %s, Max: %s%n", present(lapMsgs.stream().anyMatch(l->hasValue(l, LapMesg.EnhancedAvgSpeedFieldNum))), present(lapMsgs.stream().anyMatch(l->hasValue(l, LapMesg.EnhancedMaxSpeedFieldNum))));
             System.out.printf("  LAP (Any)| frac_ascent/descent| %s / %s%n", present(lapMsgs.stream().anyMatch(l->hasValue(l, LapMesg.TotalFractionalAscentFieldNum))), present(lapMsgs.stream().anyMatch(l->hasValue(l, LapMesg.TotalFractionalDescentFieldNum))));
             System.out.printf("  LAP (Any)| grade              | Avg: %s%n", present(lapMsgs.stream().anyMatch(l->hasValue(l, LapMesg.AvgGradeFieldNum))));
             System.out.printf("  LAP (Any)| vertical_ratio     | Avg: %s%n", present(lapMsgs.stream().anyMatch(l->hasValue(l, LapMesg.AvgVerticalRatioFieldNum))));
         } else { System.out.println("  LAP      | (No Lap Msgs)      | N/A"); }
        System.out.println("  ---------|--------------------|-----------------------");
        boolean likelyGapReady = recCount > 0 && recWithEnhAlt == recCount && recWithEnhSpeed == recCount && recWithGps == recCount;
        System.out.println("Likely GAP Ready?     : " + (likelyGapReady ? "YES (Primary enhanced fields present)" : "NO (Missing primary enhanced fields)"));

        // --- Event Summary ---
        System.out.println("── EVENT Message Summary ──────────────────────────────────");
        if (!eventMsgs.isEmpty()) { /* ... print event types ... */
             Set<String> eventTypes = eventMsgs.stream().filter(e -> e.getEventType() != null && e.getEvent() != null).map(e -> e.getEventType().toString() + " (" + e.getEvent().toString() + ")").collect(Collectors.toSet());
             System.out.printf("Distinct Event Types  : %s%n", eventTypes.isEmpty() ? "(None found)" : String.join(", ", eventTypes));
        } else { System.out.println("  (No EVENT messages found)"); }

        // --- Developer Fields ---
        System.out.println("── Developer Fields Summary ───────────────────────────────");
        if (!developerFields.isEmpty()) {
            Map<Pair<Short, Integer>, Long> distinctDevFields = developerFields.stream()
                .filter(Objects::nonNull)
                .collect(Collectors.groupingBy(
                    df -> new Pair<>(df.getDeveloperDataIndex(), df.getNum()),
                    Collectors.counting()
                ));

            System.out.printf("Found %d developer field instances.%n", developerFields.size());
            if (!distinctDevFields.isEmpty()) {
                 System.out.printf("Distinct Dev Fields (DevIndex, FieldNum): %s%n",
                     distinctDevFields.keySet().stream()
                         .map(pair -> String.format("(%d, %d)", pair.left, pair.right))
                         .collect(Collectors.joining("; ")));
            } else {
                 System.out.println("  (No distinct developer fields identified)");
            }
        } else {
            System.out.println("  (No Developer Fields found)");
        }

        // --- First/Last Record Details ---
        System.out.println("── RAW RECORD FIELD DUMPS (First 5) ─────────────────────────");
        final int DEBUG_COUNT = 5;
        int printed = 0;
        for (RecordMesg r : recordMsgs) {
             if (printed >= DEBUG_COUNT) break;
             System.out.printf("RECORD[%d] timestamp=%s%n",
                 printed,
                 r.getTimestamp() != null ? r.getTimestamp() : "n/a");
             // ** Dump ALL fields present in the record **
             for(com.garmin.fit.Field f : r.getFields()) {
                 if (f.getNumValues() > 0) {
                      System.out.printf("  %-25s (num=%2d, id=%3d) → %s%n",
                          f.getName(), f.getNumValues(), f.getNum(), f.getValue(0));
                 }
             }
             printed++;
         }
         if (recCount > DEBUG_COUNT) {
            System.out.printf("  ... (%d more records not shown)%n", recCount - DEBUG_COUNT);
         }


        System.out.println("────────────────────────────────────────────────────────────");
        System.out.println("Analysis complete for: " + inFile);
        System.out.println("────────────────────────────────────────────────────────────");
    }

    // --- dumpRecordDetails (Removed - now dumping all fields above) ---
    // private static void dumpRecordDetails(RecordMesg r) { ... }

    // --- dumpAllLapFields (Removed - now dumping details in main analysis) ---
    // private static void dumpAllLapFields(List<LapMesg> lapMsgs) { /* ... */ }

    // --- dumpFields (Unchanged) ---
    private static void dumpFields(String title, Mesg msg) {
        if (msg == null) {
             System.out.println("── " + title + " fields ───────────────────────────────────");
             System.out.println("  (Message not found or null)");
             return;
        }
        System.out.println("── " + title + " fields ───────────────────────────────────");
        for (com.garmin.fit.Field f: msg.getFields()) {
            int n = f.getNumValues();
            if (n == 0) continue;
            List<Object> vals = new ArrayList<>();
            for (int i = 0; i < n; i++) { vals.add(f.getValue(i)); }
            System.out.printf("  %-25s (num=%2d, id=%3d) → %s%n", f.getName(), f.getNumValues(), f.getNum(), vals);
        }
     }


    // ===========================================================
    //  Inject mode (Unchanged from v8 - already correct)
    // ===========================================================
    private static void inject(String[] a, boolean virtual) throws IOException {

        String inFile = a[0], outFile = a[1];
        double startLat = Double.parseDouble(a[2]);
        double startLon = Double.parseDouble(a[3]);
        float  startAlt = (a.length>4)?Float.parseFloat(a[4]):0f;
        double bearing  = (a.length>5)?Double.parseDouble(a[5]):0.0;
        double cosB=Math.cos(Math.toRadians(bearing)), sinB=Math.sin(Math.toRadians(bearing));

        final short finalSubVal = virtual
                        ? findVirtualRunValue()
                        : SubSport.GENERIC.getValue();

        List<Mesg> src = decodeAll(inFile);
        var dst = new ArrayList<Mesg>();

        // -------- 1) copy / retag non-record messages -------------
        for (Mesg m : src) {
            switch (m.getNum()) {
                case MesgNum.FILE_ID: case MesgNum.DEVICE_INFO: case MesgNum.EVENT:
                case MesgNum.USER_PROFILE: case MesgNum.HRV:
                    dst.add(m); // Copy directly
                    break;

                case MesgNum.SPORT:
                    var sm = new SportMesg(m);
                    sm.setSport(Sport.RUNNING); sm.setSubSport(SubSport.getByValue(finalSubVal)); sm.setName("Run");
                    dst.add(sm);
                    break;

                case MesgNum.SESSION:
                    var s = new SessionMesg(m);
                    s.setSport(Sport.RUNNING); s.setSubSport(SubSport.getByValue(finalSubVal)); s.setSportProfileName("Run");
                    // Keep original distance from session
                    Float originalTotalDistance = s.getTotalDistance();
                    safeRemoveField(s, SessionMesg.AvgSpeedFieldNum);
                    safeRemoveField(s, SessionMesg.MaxSpeedFieldNum);
                    safeRemoveField(s, SessionMesg.MinAltitudeFieldNum);
                    safeRemoveField(s, SessionMesg.MaxAltitudeFieldNum);
                    safeRemoveField(s, SessionMesg.EnhancedMinAltitudeFieldNum);
                    safeRemoveField(s, SessionMesg.EnhancedMaxAltitudeFieldNum);
                    dst.add(s);
                    break;

                case MesgNum.LAP:
                    var l = new LapMesg(m);
                    l.setSport(Sport.RUNNING); l.setSubSport(SubSport.getByValue(finalSubVal));
                    safeRemoveField(l, LapMesg.AvgSpeedFieldNum);
                    safeRemoveField(l, LapMesg.MaxSpeedFieldNum);
                    safeRemoveField(l, LapMesg.MinAltitudeFieldNum);
                    safeRemoveField(l, LapMesg.MaxAltitudeFieldNum);
                    dst.add(l);
                    break;

                case MesgNum.RECORD: break; // Handled later
                case MesgNum.WORKOUT: case MesgNum.WORKOUT_STEP: break; // Skip
                default: dst.add(m); break; // Copy others
            }
        }

        if (dst.stream().noneMatch(m -> m.getNum() == MesgNum.FILE_ID)) { System.err.println("Warning: No FILE_ID message."); }

        // -------- 2) Process RECORD messages: Inject GPS, Set ONLY Enhanced Alt, Remove Legacy --------
        double curLat = startLat, curLon = startLon;
        int recIdx = 0;
        Float firstDist = null, lastDist = null;
        float recMinAlt = Float.MAX_VALUE; float recMaxAlt = -Float.MAX_VALUE;
        List<RecordMesg> processedRecords = new ArrayList<>();
        Random random = new Random(); // For altitude noise

        // First pass to get all the distance values
        Map<Integer, Float> recordDistances = new HashMap<>();
        for (int i = 0; i < src.size(); i++) {
            Mesg m = src.get(i);
            if (m.getNum() == MesgNum.RECORD) {
                var r = new RecordMesg(m);
                Float dist = r.getDistance();
                if (dist != null) {
                    recordDistances.put(recIdx, dist);
                    if (firstDist == null) firstDist = dist;
                    lastDist = dist;
                }
                recIdx++;
            }
        }

        // Reset for second pass
        recIdx = 0;
        curLat = startLat;
        curLon = startLon;

        // Get the total distance from the last record or session
        float totalOriginalDist = lastDist != null ? lastDist : 0f;
        if (totalOriginalDist == 0f) {
            // Try to get from session if available
            totalOriginalDist = dst.stream()
                .filter(m -> m.getNum() == MesgNum.SESSION)
                .map(m -> new SessionMesg(m))
                .map(SessionMesg::getTotalDistance)
                .filter(d -> d != null)
                .findFirst()
                .orElse(0f);
        }

        // Calculate scaling factor to distribute GPS points appropriately
        double distanceScaleFactor = 1.0;
        if (recIdx > 1 && totalOriginalDist > 0) {
            distanceScaleFactor = totalOriginalDist / ((recIdx - 1) * GEO_STEP);
        }

        // Pre-process to make a complete time-to-distance mapping
        // This ensures we have distance values for every record, even if original data doesn't have them
        Map<DateTime, Float> timeToDistMap = new TreeMap<>(Comparator.comparing(DateTime::getTimestamp));
        List<RecordMesg> orderedRecords = new ArrayList<>();

        // First, collect all records with timestamps and distances
        float maxKnownDistance = 0f;
        DateTime firstTime = null;
        for (Mesg m : src) {
            if (m.getNum() == MesgNum.RECORD) {
                RecordMesg r = new RecordMesg(m);
                DateTime timestamp = r.getTimestamp();
                Float distance = r.getDistance();

                if (timestamp != null) {
                    if (firstTime == null) firstTime = timestamp;
                    orderedRecords.add(r);

                    // If this record has a distance, store it
                    if (distance != null) {
                        timeToDistMap.put(timestamp, distance);
                        if (distance > maxKnownDistance) {
                            maxKnownDistance = distance;
                        }
                    }
                }
            }
        }

        // Get total distance from session if available and greater than what we found in records
        float sessionTotalDistance = dst.stream()
            .filter(m -> m.getNum() == MesgNum.SESSION)
            .map(m -> new SessionMesg(m))
            .map(SessionMesg::getTotalDistance)
            .filter(d -> d != null && d > 0)
            .findFirst()
            .orElse(maxKnownDistance);

        if (sessionTotalDistance > maxKnownDistance) {
            maxKnownDistance = sessionTotalDistance;
        }

        // Now interpolate distances for records missing distance data
        List<DateTime> knownTimes = new ArrayList<>(timeToDistMap.keySet());
        Collections.sort(knownTimes, Comparator.comparing(DateTime::getTimestamp));

        if (!knownTimes.isEmpty()) {
            // Handle records before first known distance
            DateTime firstKnownTime = knownTimes.get(0);
            float firstKnownDist = timeToDistMap.get(firstKnownTime);

            // Handle records after last known distance
            DateTime lastKnownTime = knownTimes.get(knownTimes.size() - 1);
            float lastKnownDist = timeToDistMap.get(lastKnownTime);

            // Linear interpolation for each record without distance
            for (RecordMesg r : orderedRecords) {
                DateTime timestamp = r.getTimestamp();

                if (!timeToDistMap.containsKey(timestamp)) {
                    // This record needs a distance value interpolated
                    if (timestamp.getTimestamp() < firstKnownTime.getTimestamp()) {
                        // Before first known distance - assume constant pace
                        long timeDiff = timestamp.getTimestamp() - firstTime.getTimestamp();
                        long firstTimeDiff = firstKnownTime.getTimestamp() - firstTime.getTimestamp();
                        if (firstTimeDiff > 0) {
                            float dist = firstKnownDist * timeDiff / firstTimeDiff;
                            timeToDistMap.put(timestamp, dist);
                        } else {
                            timeToDistMap.put(timestamp, 0f); // Same time as first
                        }
                    } else if (timestamp.getTimestamp() > lastKnownTime.getTimestamp()) {
                        // After last known distance - extrapolate with last known pace
                        if (knownTimes.size() >= 2) {
                            DateTime prevKnownTime = knownTimes.get(knownTimes.size() - 2);
                            float prevKnownDist = timeToDistMap.get(prevKnownTime);

                            long lastTimeDiff = lastKnownTime.getTimestamp() - prevKnownTime.getTimestamp();
                            float lastDistDiff = lastKnownDist - prevKnownDist;

                            if (lastTimeDiff > 0) {
                                float pace = lastDistDiff / lastTimeDiff; // meters per timestamp unit
                                long extraTimeDiff = timestamp.getTimestamp() - lastKnownTime.getTimestamp();
                                float extraDist = pace * extraTimeDiff;
                                timeToDistMap.put(timestamp, lastKnownDist + extraDist);
                            } else {
                                // Same timestamp - use same distance
                                timeToDistMap.put(timestamp, lastKnownDist);
                            }
                        } else {
                            // Only one known point - linear extrapolation to session total
                            long totalTimeDiff = orderedRecords.get(orderedRecords.size() - 1).getTimestamp().getTimestamp() - firstTime.getTimestamp();
                            long currentTimeDiff = timestamp.getTimestamp() - firstTime.getTimestamp();

                            if (totalTimeDiff > 0) {
                                float dist = maxKnownDistance * currentTimeDiff / totalTimeDiff;
                                timeToDistMap.put(timestamp, dist);
                            } else {
                                timeToDistMap.put(timestamp, maxKnownDistance); // Fallback
                            }
                        }
                    } else {
                        // Between known distances - find surrounding points and interpolate
                        DateTime before = null;
                        DateTime after = null;

                        for (DateTime knownTime : knownTimes) {
                            if (knownTime.getTimestamp() <= timestamp.getTimestamp()) {
                                before = knownTime;
                            } else {
                                after = knownTime;
                                break;
                            }
                        }

                        if (before != null && after != null) {
                            float beforeDist = timeToDistMap.get(before);
                            float afterDist = timeToDistMap.get(after);
                            long beforeTime = before.getTimestamp();
                            long afterTime = after.getTimestamp();
                            long totalDiff = afterTime - beforeTime;

                            if (totalDiff > 0) {
                                float ratio = (float)(timestamp.getTimestamp() - beforeTime) / totalDiff;
                                float interpolatedDist = beforeDist + ratio * (afterDist - beforeDist);
                                timeToDistMap.put(timestamp, interpolatedDist);
                            } else {
                                timeToDistMap.put(timestamp, beforeDist); // Same time point
                            }
                        }
                    }
                }
            }
        } else if (sessionTotalDistance > 0) {
            // No known distances in records, but we have session total
            // Distribute evenly based on timestamp
            if (!orderedRecords.isEmpty()) {
                DateTime firstRecordTime = orderedRecords.get(0).getTimestamp();
                DateTime lastRecordTime = orderedRecords.get(orderedRecords.size() - 1).getTimestamp();
                long totalDuration = lastRecordTime.getTimestamp() - firstRecordTime.getTimestamp();

                if (totalDuration > 0) {
                    for (RecordMesg r : orderedRecords) {
                        DateTime timestamp = r.getTimestamp();
                        long elapsed = timestamp.getTimestamp() - firstRecordTime.getTimestamp();
                        float dist = sessionTotalDistance * elapsed / totalDuration;
                        timeToDistMap.put(timestamp, dist);
                    }
                }
            }
        }

        // Now process records with accurate distance-based GPS points
        recIdx = 0;
        float lastInterpolatedDist = 0f;

        for (RecordMesg originalRecord : orderedRecords) {
            var r = new RecordMesg(originalRecord);
            DateTime timestamp = r.getTimestamp();

            // Get the interpolated/original distance for this record
            Float recordDist = timeToDistMap.get(timestamp);
            if (recordDist == null) {
                // This shouldn't happen with our interpolation, but just in case
                recordDist = lastInterpolatedDist;
            } else {
                lastInterpolatedDist = recordDist;
            }

            // Store first and last distance values
            if (recIdx == 0) {
                firstDist = recordDist;
            }
            lastDist = recordDist;

            // Calculate GPS position based on distance traveled
            double distTraveled = recordDist - (firstDist != null ? firstDist : 0);
            curLat = startLat + m2degLat(distTraveled * cosB);
            curLon = startLon + m2degLon(distTraveled * sinB, curLat);

            // Set GPS coordinates for this record
            r.setPositionLat(toSemi(curLat));
            r.setPositionLong(toSemi(curLon));

            // Calculate altitude based on the distance and grade
            // Convert all values to float explicitly to avoid lossy conversion errors
            float targetAlt = startAlt + (float)(distTraveled) * (float)GRADE;

            // Add random noise to the target altitude if configured
            float alt = targetAlt + (float)(random.nextDouble() - 0.5) * (float)ALTITUDE_NOISE_FACTOR;

            // Update the record with altitude
            r.setEnhancedAltitude(alt);

            // Track min/max altitude
            if (alt < recMinAlt) recMinAlt = alt;
            if (alt > recMaxAlt) recMaxAlt = alt;

            // Remove legacy fields
            safeRemoveField(r, RecordMesg.AltitudeFieldNum);
            safeRemoveField(r, RecordMesg.SpeedFieldNum);

            // Preserve original distance if it exists
            if (r.getDistance() == null && recordDist != null) {
                r.setDistance(recordDist);
            }

            processedRecords.add(r);
            recIdx++;
        }
        dst.addAll(processedRecords);

        if (recIdx == 0) { /* Handle no records */ }
        else { System.out.printf("✔  Processed %d record(s)%n", recIdx); }

        // -------- 3) Use original distance for final summary values --------
        final float finalSessionMinAlt = (recIdx > 0 && recMinAlt != Float.MAX_VALUE) ? recMinAlt : startAlt;
        final float finalSessionMaxAlt = (recIdx > 0 && recMaxAlt != -Float.MAX_VALUE) ? recMaxAlt : startAlt;
        final int finalFirstLat = toSemi(startLat);
        final int finalFirstLon = toSemi(startLon);
        final int finalLastLat  = toSemi(curLat);
        final int finalLastLon  = toSemi(curLon);

        // Use the original total distance from the activity
        final float finalTotalDist = Math.max(0f, totalOriginalDist);

        // Calculate overall ascent based on TRACKED min/max altitude (reflects noise)
        final int finalTotalAscent = Math.round(Math.max(0f, finalSessionMaxAlt - finalSessionMinAlt));
        final int finalTotalDescent = 0;
        final float finalFracAscent = finalTotalDist > 1e-6 ? (float)finalTotalAscent / finalTotalDist : 0f;
        final float finalFracDescent = finalTotalDist > 1e-6 ? (float)finalTotalDescent / finalTotalDist : 0f;
        final double finalBearing = bearing;

        // -------- 4) Update SESSION/LAP messages with correct distance values --------
        dst.replaceAll(m -> switch (m.getNum()) {
            case MesgNum.SESSION -> {
                var s = new SessionMesg(m);
                s.setSubSport(SubSport.getByValue(finalSubVal));
                s.setSportProfileName("Run");
                s.setStartPositionLat(finalFirstLat);
                s.setStartPositionLong(finalFirstLon);
                s.setEndPositionLat(finalLastLat);
                s.setEndPositionLong(finalLastLon);
                s.setTotalDistance(finalTotalDist); // Use original distance
                s.setTotalAscent(finalTotalAscent);
                s.setTotalDescent(finalTotalDescent);
                s.setTotalFractionalAscent(finalFracAscent);
                s.setTotalFractionalDescent(finalFracDescent);

                // Ensure specific fields are removed from Session
                safeRemoveField(s, SessionMesg.EnhancedMinAltitudeFieldNum);
                safeRemoveField(s, SessionMesg.EnhancedMaxAltitudeFieldNum);
                safeRemoveField(s, SessionMesg.AvgSpeedFieldNum);
                safeRemoveField(s, SessionMesg.MaxSpeedFieldNum);
                safeRemoveField(s, SessionMesg.MinAltitudeFieldNum);
                safeRemoveField(s, SessionMesg.MaxAltitudeFieldNum);

                // Bounding box
                int swLat = (finalBearing > 90 && finalBearing < 270) ? finalLastLat : finalFirstLat;
                int swLon = (finalBearing > 180 && finalBearing < 360) ? finalLastLon : finalFirstLon;
                int neLat = (finalBearing <= 90 || finalBearing >= 270) ? finalLastLat : finalFirstLat;
                int neLon = (finalBearing >= 0 && finalBearing <= 180) ? finalLastLon : finalFirstLon;
                s.setFieldValue((short)31, 0, swLat);
                s.setFieldValue((short)32, 0, swLon);
                s.setFieldValue((short)29, 0, neLat);
                s.setFieldValue((short)30, 0, neLon);
                yield s;
            }
            case MesgNum.LAP -> {
                var l = new LapMesg(m);
                l.setSubSport(SubSport.getByValue(finalSubVal));
                Float lapDist = l.getTotalDistance();
                // Preserve original lap distance
                int lapAscent = 0;
                float lapFracAscent = 0f;

                // Calculate ascent based on grade and original distance
                if (lapDist != null && lapDist > 0) {
                    lapAscent = Math.round(lapDist * (float)GRADE);
                    lapFracAscent = (float)lapAscent / lapDist;
                }
                int lapDescent = 0;
                float lapFracDescent = 0f;

                l.setTotalAscent(lapAscent);
                l.setTotalDescent(lapDescent);
                l.setTotalFractionalAscent(lapFracAscent);
                l.setTotalFractionalDescent(lapFracDescent);
                l.setStartPositionLat(finalFirstLat);
                l.setStartPositionLong(finalFirstLon);
                l.setEndPositionLat(finalLastLat);
                l.setEndPositionLong(finalLastLon);
                l.setEnhancedMinAltitude(finalSessionMinAlt);
                l.setEnhancedMaxAltitude(finalSessionMaxAlt);

                safeRemoveField(l, LapMesg.AvgSpeedFieldNum);
                safeRemoveField(l, LapMesg.MaxSpeedFieldNum);
                safeRemoveField(l, LapMesg.MinAltitudeFieldNum);
                safeRemoveField(l, LapMesg.MaxAltitudeFieldNum);
                yield l;
            }
            default -> m;
        });

        // -------- 5) encode FIT -----------------------------------
        FileEncoder enc = new FileEncoder(new java.io.File(outFile), Fit.ProtocolVersion.V2_0);
        dst.stream().filter(m -> m.getNum() == MesgNum.FILE_ID).findFirst().ifPresent(enc::write);
        for (Mesg m : dst) {
            if (m.getNum() != MesgNum.FILE_ID) {
            try { enc.write(m); } catch (FitRuntimeException fitEx) {
                System.err.printf("ERROR encoding message %s: %s%n", m.getName(), fitEx.getMessage());
                dumpFields("Failed Message: " + m.getName(), m);
            }
            }
        }
        enc.close();
        System.out.printf("✔  Written %s (%d msgs)%n", outFile, dst.size());
    }


    // ===========================================================
    //  decode helper (Unchanged)
    // ===========================================================
    private static List<Mesg> decodeAll(String file) throws IOException {
        var list = new ArrayList<Mesg>();
        try (InputStream in = new FileInputStream(file)) {
            Decode d = new Decode();
            MesgBroadcaster bc = new MesgBroadcaster();
            bc.addListener((Mesg m) -> list.add(m));
            d.read(in, bc, bc);
        } catch (FitRuntimeException e) {
             System.err.println("Error decoding FIT file: " + file);
             e.printStackTrace(); throw e;
        }
        return list;
    }

    // ===========================================================
    //  SAFE lookup of "virtual" raw value (Unchanged)
    // ===========================================================
    private static short findVirtualRunValue() {
        final short VIRTUAL_RUN_VALUE = 18; final short VIRTUAL_ACTIVITY_VALUE = 58;
        try { java.lang.reflect.Field f = SubSport.class.getField("VIRTUAL_RUN"); Object v = f.get(null); if (v instanceof SubSport ss) { if (ss.getValue() == VIRTUAL_RUN_VALUE) return VIRTUAL_RUN_VALUE; } } catch (Exception ignored) {}
        try { java.lang.reflect.Field f = SubSport.class.getField("VIRTUAL_ACTIVITY"); Object v = f.get(null); if (v instanceof SubSport ss) { if (ss.getValue() == VIRTUAL_ACTIVITY_VALUE) { System.out.println("Using SubSport.VIRTUAL_ACTIVITY (" + VIRTUAL_ACTIVITY_VALUE + ") as fallback."); return VIRTUAL_ACTIVITY_VALUE; } } } catch (Exception ignored) {}
        System.out.println("Warning: Could not reflectively find VIRTUAL_RUN/ACTIVITY SubSport. Using hard-wired value: " + VIRTUAL_RUN_VALUE);
        return VIRTUAL_RUN_VALUE;
    }
} // End of AddInclineFitGem class
