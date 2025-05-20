package com.yourco;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.nio.file.Files;
import java.util.UUID;
import java.util.ArrayList;
import java.util.List;

@RestController
public class InjectController {

    @PostMapping(path = "/inject", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<byte[]> inject(
            @RequestParam(name="file") MultipartFile file,
            @RequestParam(name="lat",     defaultValue="42.036369") double lat,
            @RequestParam(name="lon",     defaultValue="-91.638498") double lon,
            @RequestParam(name="alt",     defaultValue="0")      float alt,
            @RequestParam(name="bearing", defaultValue="0")      double bearing,
            @RequestParam(name="virtual", defaultValue="false")   boolean virtual,
            @RequestParam(name="grade",   defaultValue="0.10")    double grade,
            @RequestParam(name="name",    required=false)         String outputName
            ) throws Exception {

                // 1. Save the uploaded FIT to a temp file
                File in  = File.createTempFile("in-",  ".fit");
                file.transferTo(in);

                // 2. Prepare an output temp file
                File out = File.createTempFile("out-", ".fit");

                // 3. Build the args array for in-process injection
                String[] injectorArgs = new String[] {
                    in.getAbsolutePath(),
                    out.getAbsolutePath(),
                    String.valueOf(lat),
                    String.valueOf(lon),
                    String.valueOf(alt),
                    String.valueOf(bearing),
                    "--grade",
                    String.valueOf(grade),
                    virtual ? "--virtual" : ""
                };

                // 4. Call your injector directly (no forked JVM)
                AddInclineFitGem.main(injectorArgs);

                // 5. Determine the download filename
                String dlName;
                if (outputName != null && !outputName.isBlank()) {
                    dlName = outputName;
                } else {
                    String orig = file.getOriginalFilename();
                    String base = (orig != null)
                                  ? orig.replaceFirst("\\.fit$", "")
                                  : "output";
                    dlName = base + "_injected_grade_" + (int)(grade * 100) + ".fit";
                }

                // 6. Read the injected FIT bytes
                byte[] body = Files.readAllBytes(out.toPath());

                // 7. Return as an attachment
                return ResponseEntity.ok()
                        .header(HttpHeaders.CONTENT_DISPOSITION,
                                "attachment; filename=\"" + dlName + "\"")
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .body(body);
            }
        }