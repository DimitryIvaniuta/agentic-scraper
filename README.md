# Agentic Scraper

A Spring Boot microservice that provides unified REST endpoints for:

* **MPN Search**: Lookup manufacturer part numbers (MPNs) across multiple vendors (e.g., Murata, TDK).
* **Cross-Reference**: Find equivalent parts between competitors and vendor catalogs.
* **Parametric Search**: Filter components by technical parameters (size, capacitance, inductance, etc.).

---

## Features

* Vendor‑agnostic architecture using an abstract `VendorSearchEngine`.
* HTTP‑based scraping (`RestClient`)—no browser automation required for JSON‑backed APIs.
* Configurable via `application.yml` under `vendors:` (base URLs, categories, paths).
* Modular JSON grid parsing with `JsonGridParser` implementations per vendor.
* Spring MVC controllers:

    * **MPN Search**: `POST /api/search/mpn` → request: `{ "mpn": "GRM0115C1C100GE01", "vendor": "murata" }`
    * **Cross-Reference**: `POST /api/search/cross-ref?vendor=murata` → body: `{ "competitorMpn": "XAL4020152ME", "categoryPath": ["Inductors"] }`
    * **Parametric**: `POST /api/search/parametric?vendor=murata` → `ParametricSearchRequest` JSON (category, subcategory, filters, maxResults)

---

## Prerequisites

* Java 17+ / JDK 11+ compatible
* Maven 3.6+ or Gradle
* Internet access to vendor APIs

---

## Getting Started

1. **Clone the repository**:

   ```bash
   git clone https://your.repo.url/agentic-scraper.git
   cd agentic-scraper
   ```

2. **Configure vendors** in `src/main/resources/application.yml`:

   ```yaml
   vendors:
     murata:
       base-url: https://www.murata.com
       mpn-search-path: /webapi/PsdispRest
       cross-ref-url: /webapi/SearchCrossReference
       parametric-search-url: /webapi/PsdispRest
       default-cate: luCeramicCapacitorsSMD
       cross-ref-default-cate: cgInductorscrossreference
       categories:
         GRM: luCeramicCapacitorsSMD
         LQH: luInductorSMD
         # ...
       cross-ref-categories:
         XAL: cgInductorscrossreference
         # ...

     tdk:
       base-url: https://product.tdk.com
       mpn-search-path: /en/search/productsearch
       cross-ref-url: /crossreference
       parametric-search-url: /webapi/PsdispRest
       default-cate: inductors
       # ...
   ```

3. **Build & Run**:

   ```bash
   mvn clean package
   java -jar target/agentic-scraper-1.0.0.jar
   ```

---

## API Usage Examples

### MPN Search

```bash
curl -X POST http://localhost:8080/api/search/mpn \
     -H 'Content-Type: application/json' \
     -d '{ "mpn": "GRM0115C1C100GE01#", "vendor": "murata" }'
```

*Response*:

```json
[
  { "Part Number": "GRM0115C1C100GE01#", "Status": "In Production", ... }
]
```

### Cross-Reference

```bash
curl -X POST 'http://localhost:8080/api/search/cross-ref?vendor=murata' \
     -H 'Content-Type: application/json' \
     -d '{
       "competitorMpn": "XAL4020152ME",
       "categoryPath": ["Inductors"]
     }'
```

*Response*:

```json
[
  { "table":"competitor", "records":[{ "Part Number":"XAL4020152ME", ... }] },
  { "table":"murata",     "records":[{ "Part Number":"DFE322520F-1R5M#", ... }] }
]
```

### Parametric Search

```bash
curl -X POST 'http://localhost:8080/api/search/parametric?vendor=murata' \
     -H 'Content-Type: application/json' \
     -d '{
       "category":"Capacitors",
       "subcategory":"",
       "mpn":"GRM0115C1C",
       "parameters": {
         "capacitance": {"min":10, "max":125},
         "ratedVoltageDC": [230,240]
       },
       "maxResults": 50
     }'
```

*Response*:

```json
[
  { "Part Number":"GRM0115C1C100GE01#", "Length":"0.25±0.013mm", ... }
]
```

---

## Extending to New Vendors

1. **Add vendor config** in `application.yml` under `vendors:`.
2. **Implement**:

    * `VendorHttpMpnSearchService` (extend `VendorSearchEngine`, implement `MpnSearchService`).
    * `VendorHttpCrossReferenceSearchService` (implement `CrossReferenceSearchService`).
    * `VendorHttpParametricSearchService` (implement `ParametricSearchService`).
    * `JsonGridParser` for JSON grid format if needed.
3. **Register beans** via `@Service("<vendor>...Svc")` and ensure naming matches controller lookup.

---

## Logging & Error Handling

* **4xx** JSON not found returns empty result.
* **5xx** errors bubble as HTTP 500.
* Controllers handle `IllegalArgumentException` → HTTP 400 with JSON error message.

