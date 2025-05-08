# Examples & Postman Collections

This document provides:

* A ready-to-import Postman collection for all three API endpoints
* Environment setup for Postman
* Sample request payloads and `curl` equivalents

---

## Postman Collection Import

1. Locate the Postman collection file at `docs/AgenticScraper.postman_collection.json` in this repository.
2. Open Postman and click **Import** ▶️ **File**.
3. Select `docs/AgenticScraper.postman_collection.json` and confirm.
4. After import, select the **AgenticScraper** collection in the sidebar.
5. Create or select an environment (e.g., `AgenticScraper`) and add variables:

    * `baseUrl` = `http://localhost:8080`
    * `OPENAI_API_KEY` = `<your OpenAI API key>` (if using AI category detection)

---

## Environment Variables

| Variable         | Description                           | Example                 |
| ---------------- | ------------------------------------- | ----------------------- |
| `baseUrl`        | Base URL of the running service       | `http://localhost:8080` |
| `OPENAI_API_KEY` | OpenAI API key for category detection | `sk-....`               |

---

## Sample Requests

### 1. MPN Search

* **Request**

    * Method: `POST`
    * URL: `{{baseUrl}}/api/search/mpn`
    * Headers:

        * `Content-Type: application/json`
    * Body (raw JSON):

      ```json
      {
        "mpn": "GRM0115C1C100GE01",
        "vendor": "murata"
      }
      ```

* **`curl` Equivalent**

  ```bash
  curl -X POST "{{baseUrl}}/api/search/mpn?vendor=murata" \
       -H "Content-Type: application/json" \
       -d '{"mpn":"GRM0115C1C100GE01"}'
  ```

### 2. Parametric Search

* **Request**

    * Method: `POST`
    * URL: `{{baseUrl}}/api/search/parametric?vendor=murata`
    * Headers:

        * `Content-Type: application/json`
    * Body (raw JSON):

      ```json
      {
        "category": "Capacitors",
        "subcategory": "Ceramic Capacitors(SMD)",
        "mpn": "GRM0115C",
        "parameters": {
          "ceramicCapacitors-capacitance": { "min": 10, "max": 125 },
          "ceramicCapacitors-ratedVoltageDC": { "min": 230 }
        },
        "maxResults": 50
      }
      ```

* **`curl` Equivalent**

  ```bash
  curl -X POST "{{baseUrl}}/api/search/parametric?vendor=murata" \
       -H "Content-Type: application/json" \
       -d '{
             "category":"Capacitors",
             "subcategory":"Ceramic Capacitors(SMD)",
             "mpn":"GRM0115C",
             "parameters":{
               "ceramicCapacitors-capacitance":{"min":10,"max":125},
               "ceramicCapacitors-ratedVoltageDC":{"min":230}
             },
             "maxResults":50
           }'
  ```

### 3. Cross-Reference Search

* **Request**

    * Method: `POST`
    * URL: `{{baseUrl}}/api/search/cross-ref?vendor=murata`
    * Headers:

        * `Content-Type: application/json`
    * Body (raw JSON):

      ```json
      {
        "competitorMpn": "XAL4020152ME",
        "categoryPath": [
          "Inductors",
          "Power Inductors"
        ]
      }
      ```

* **`curl` Equivalent**

  ```bash
  curl -X POST "{{baseUrl}}/api/search/cross-ref?vendor=murata" \
       -H "Content-Type: application/json" \
       -d '{
             "competitorMpn":"XAL4020152ME",
             "categoryPath":["Inductors","Power Inductors"]
           }'
  ```

---

For more details on response formats and field descriptions, see the main [README.md](../README.md).
