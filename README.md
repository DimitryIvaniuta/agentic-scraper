# Agentic Scraper

A Spring Boot–based scraper framework for electronic component manufacturers (e.g., Murata, TDK). Supports:

* **MPN Search**: Lookup manufacturer part numbers via HTTP or Selenium.
* **Cross‑Reference Search**: Map competitor part numbers to manufacturer equivalents.
* **Parametric Search**: Filter catalogs by electrical/mechanical parameters.
* **AI‑powered Category Detection**: Leverage OpenAI to infer the correct `cate` code from an MPN.

---

## Table of Contents

1. [Features](#features)
2. [Prerequisites](#prerequisites)
3. [Getting Started](#getting-started)
4. [Configuration](#configuration)

    * [.env](#env)
    * `application.yml`
    * [OpenAI Properties](#openai-properties)
5. [OpenAI Integration](#openai-integration)
6. [Running the Application](#running-the-application)
7. [API Endpoints](#api-endpoints)

    * [MPN Search](#mpn-search)
    * [Cross‑Reference Search](#cross-reference-search)
    * [Parametric Search](#parametric-search)
8. [Examples](#examples)
9. [Extending with New Vendors](#extending-with-new-vendors)

---

## Features

- **MPN Search**:  
  Returns detailed specs & detail-page URL for a given MPN.
- **Parametric Search**:  
  Filter across categories & subcategories with arbitrary numeric/range/list filters.
- **Cross-Reference Search**:  
  Lookup Murata equivalents to a competitor’s MPN.

- **AI Category Detection** (optional):  
  Uses OpenAI GPT (default `gpt-3.5-turbo`) + Resilience4j to infer vendor category codes when prefix rules are insufficient.
- **Vendor Abstraction**:  
  Shared `VendorHttpSearchEngine` + per-vendor implementations (e.g. `MurataHttpMpnSearchService`, `TdkHttpParametricSearchService`).
- **Configurable** via `application.yml` + `.env` for secrets.
---
## Prerequisites

- **Java 17+** (tested on 21)
- **Gradle 7+** or **Maven 3.8+**
- **OpenAI API Key** (if using AI category lookup)
- Internet access to vendor APIs

---
## Getting Started

1. Clone this repository:

   ```bash
   git clone https://github.com/your-org/agentic-scraper.git
   cd agentic-scraper
   ```
2. Create a `.env` file in the project root (see below).
3. Adjust `application.yml` with your vendor configs and OpenAI settings.
4. Build and run:

   ```bash
   ./gradlew bootRun
   ```

---

## Configuration

### `.env`

Store secrets and environment‑specific variables here (DO NOT commit this file). Example:

```dotenv
# OpenAI credentials
OPENAI_API_KEY=sk-<your-secret-key>
OPENAI_MODEL=gpt-3.5-turbo
```

### `application.yml`

```yaml
spring:
  config:
    import: "optional:dotenv:.env"
vendors:
  murata:
    base-url: https://www.murata.com
    base-url-sitesearch: https://sitesearch.murata.com
    mpn-search-path: /webapi/PsdispRest
    mpn-search-product: /search/product
    cross-ref-url: /webapi/SearchCrossReference
    parametric-search-url: /webapi/PsdispRest
    default-cate: luCeramicCapacitorsSMD
    cross-ref-default-cate: cgInductorscrossreference
    categories:
      GRM: luCeramicCapacitorsSMD
      LQH: luInductorSMD
      # ... more prefixes ...
    cross-ref-categories:
      GRM: cgCapacitorscrossreference
      LQH: cgInductorscrossreference
tdk:
  base-url: https://product.tdk.com
  mpn-search-path: /en/search/productsearch
  cross-ref-url: /crossreference

openai:
  api:
    key: ${OPENAI_API_KEY}
    base-url: https://api.openai.com/v1
    default-model: ${OPENAI_MODEL:gpt-3.5-turbo}

logging:
  level:
    root: INFO
    org.springframework: DEBUG
```

#### Notes

* `spring.config.import: "optional:dotenv:.env"` loads your `.env`.
* `${OPENAI_API_KEY}` is injected into `OpenAIProperties`.

---

## OpenAI Integration

The `LLMHelper` component performs a chat‐completion request to OpenAI:

1. **Prompt**: *"You are an expert on Murata's API. Given the part number "{PARTNO}" return only the exact \`cate\` value to use in PsdispRest calls."*
2. **Resilience**: Wrapped in Resilience4j retry + circuit breaker. On failure/timeout, falls back to prefix‐map lookup.
3. **Model**: Defaults to `gpt-3.5-turbo`, configurable via `OPENAI_MODEL`.

Make sure you have valid billing to avoid 429/insufficient\_quota errors.

---

## Running the Application

```bash
# Build
./gradlew clean build

# Run
./gradlew bootRun
```

The service starts on `http://localhost:8080` by default.

---

## API Endpoints

### MPN Search

```http
POST /api/search/mpn?vendor=murata
Content-Type: application/json

{
    "mpn":"GRM0115C1C100GE01"
}
```

### Cross‑Reference Search

```http
POST /api/search/cross-ref?vendor=murata
Content-Type: application/json

{
  "competitorMpn": "XAL4020152ME",
  "categoryPath": ["Inductors"]
}
```

### Parametric Search

```http
POST /api/search/parametric?vendor=murata
Content-Type: application/json

     {
        "category": "Capacitors",
        "subcategory": "Ceramic Capacitors(SMD)",
        "parameters": {
          "mpn": "GRM022R61A121",
          "capacitance": 10,
          "ratedVoltageDC": {"min": 100, "max": 200},
          "characteristic": ["C0G", "X7R"]
        },
        "maxResults": 100
      }
```

---

## Examples

See the [Examples](docs/EXAMPLES.md) for Postman collections and sample payloads.

---

## Extending with New Vendors

1. Create a new `VendorCfg` entry in `application.yml` under `vendors`.
2. Implement `HttpMpnSearchService`, `HttpCrossReferenceSearchService`, `ParametricSearchService`.
3. Register parsers and services with Spring (`@Service("<vendor>ParamSvc")`, etc.).
