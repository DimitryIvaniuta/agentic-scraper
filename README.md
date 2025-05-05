# Agentic Scraper

A Spring Boot–based scraper framework for electronic component manufacturers (e.g., Murata, TDK). Supports:

* **MPN Search**: Lookup manufacturer part numbers via HTTP or Selenium.
* **Cross‑Reference Search**: Map competitor part numbers to manufacturer equivalents.
* **Parametric Search**: Filter catalogs by electrical/mechanical parameters.
* **AI‑powered Category Detection**: Leverage OpenAI to infer the correct `cate` code from an MPN.

---

## Table of Contents

1. [Features](#features)
2. [Getting Started](#getting-started)
3. [Configuration](#configuration)

    * [.env](#env)
    * `application.yml`
    * [OpenAI Properties](#openai-properties)
4. [OpenAI Integration](#openai-integration)
5. [Running the Application](#running-the-application)
6. [API Endpoints](#api-endpoints)

    * [MPN Search](#mpn-search)
    * [Cross‑Reference Search](#cross-reference-search)
    * [Parametric Search](#parametric-search)
7. [Examples](#examples)
8. [Extending with New Vendors](#extending-with-new-vendors)
9. [License](#license)

---

## Features

* **Modular Design**: Abstract `VendorHttpSearchEngine` base, per-vendor implementations.
* **Configurable**: All endpoints, categories, and credentials live in `application.yml` or `.env`.
* **AI‑Assisted**: Uses LLMHelper + OpenAI to determine manufacturer category codes from part numbers, falling back to prefix‐map if needed.
* **Resilience**: Retries and circuit breakers via Resilience4j for AI calls.

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
    mpn-search-path: /webapi/PsdispRest
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
POST /api/search/mpn
Content-Type: application/json

{
  "vendor": "murata",
  "mpn": "GRM0115C1C100GE01#"
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
  "subcategory": "Ceramic Capacitors",
  "parameters": {
    "capacitance": { "min": 10, "max": 100 },
    "ratedVoltageDC": 16,
    "characteristic": ["C0G", "X7R"]
  },
  "maxResults": 50
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
