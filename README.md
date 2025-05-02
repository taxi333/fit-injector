# FIT Injector

This is a simple application to inject a grade into a treadmill workout `.fit` file by adding synthetic GPS and incline data based on user-specified parameters.

## Features

* **Grade Injection**: Insert a user-defined incline percentage into a `.fit` file.
* **Location Simulation**: Specify latitude, longitude, altitude, and bearing.
* **Virtual Run Option**: Mark a run as virtual via a checkbox.
* **REST API**: Upload and process `.fit` files through a POST endpoint.
* **Mobile-Friendly UI**: Single-page web interface optimized for iPhone 16.
* **Dockerized**: Build and run using Docker and Docker Compose.

## Prerequisites

* Java 21
* Maven 3.8+
* Docker & Docker Compose (optional, for containerized setup)

## Installation

1. **Clone the repository**

   ```bash
   git clone https://github.com/yourco/fit-injector.git
   cd fit-injector
   ```

2. **Build locally**

   ```bash
   mvn clean package
   ```

3. **Run the application**

   ```bash
   # Option A: Spring Boot
   mvn spring-boot:run

   # Option B: Packaged JAR
   java -jar target/fit-injector-1.0.0.jar
   ```

## Usage

1. Open your browser (or iPhone) to `http://localhost:8080/`.
2. Upload your treadmill `.fit` file.
3. Enter the start latitude, longitude, altitude, bearing, and desired grade (%).
4. (Optional) Check "Mark as Virtual Run".
5. (Optional) Specify an output filename; otherwise, the default is `<original>_injected_grade_<grade>.fit`.
6. Click **Inject** and download the modified file.

### API Endpoint

* **POST** `/inject`

  * **Form Fields**:

    * `file`: `.fit` file upload
    * `lat`: starting latitude (default `37.7749`)
    * `lon`: starting longitude (default `-122.4194`)
    * `alt`: altitude in meters (default `0`)
    * `bearing`: bearing in degrees (default `0`)
    * `grade`: incline percentage (default `10`)
    * `virtual`: `true`/`false` (default `false`)
    * `name`: optional output filename
  * **Response**: `200 OK` with `application/octet-stream` body containing the injected `.fit` file.

## Docker

1. **Build and run**:

   ```bash
   docker compose up --build -d
   ```
2. The service will be available at `http://localhost:8080/`.

## Docker Compose

```yaml
services:
  fit-injector:
    build: .
    ports:
      - "8080:8080"
    restart: unless-stopped
```

## Contributing

Contributions are welcome! Please fork the repo and open a pull request for any improvements or bug fixes.

## License

This project is licensed under the MIT License. See the [LICENSE](LICENSE) file for details.

