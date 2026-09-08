# Library API

A Quarkus application that automatically generates REST APIs from DMN (Decision Model and Notation) files. **DMN files are "source code" for JSON web APIs**—add a DMN file, get a REST endpoint.

---

## Quick Start

### View the DMN models

Use VS Code w/ the [DMN Editor Extension](https://marketplace.visualstudio.com/items?itemName=kie-group.dmn-vscode-extension)

The models are in `src/main/resources`

### Run the Server

```bash
cd library-api
bin/dev
```

Serves at **http://localhost:8083** with:
- Swagger UI: http://localhost:8083/q/swagger-ui
- OpenAPI spec: http://localhost:8083/q/openapi

### Your First API Call

```bash
curl -X POST http://localhost:8083/api/v1/checks/age/person-min-age \
  -H "Content-Type: application/json" \
  -d '{
    "situation": {
      "primaryPersonId": "p1",
      "people": [{"id": "p1", "dateOfBirth": "1950-01-01"}]
    },
    "parameters": {"minAge": 65, "personId": "p1", "asOfDate": "2025-01-01"}
  }'
```

Returns: `{"checkResult": true, ...}`

Explore all endpoints in Swagger UI or check `test/bdt/` for more examples.

**New to DMN?** [Learn DMN in 15 Minutes](https://learn-dmn-in-15-minutes.com/)

---

## Core Concepts

### DMN → REST Endpoints

Each DMN file in `src/main/resources/` becomes a REST endpoint:

```
File: src/main/resources/checks/age/person-min-age.dmn
   └─ Decision Service: PersonMinAgeService
      └─ Generates: POST /api/v1/checks/age/person-min-age
```

**Pattern**: `POST /api/v1/{path-to-dmn-without-extension}`

### Checks + Benefits Architecture

**Checks** = Reusable eligibility logic (e.g., "is person 65+?")
- Location: `src/main/resources/checks/{category}/`
- Example: `checks/age/person-min-age.dmn` → `/api/v1/checks/age/person-min-age`

**Benefits** = Specific programs that compose checks (e.g., "Homestead Exemption")
- Location: `src/main/resources/benefits/{jurisdiction}/`
- Example: `benefits/pa/phl/homestead-exemption.dmn` → `/api/v1/benefits/pa/phl/homestead-exemption`

### The tSituation Data Model

Standard input for all endpoints. The situation upon which you'd like to compute some benefit's eligibility:

```javascript
{
  "situation": {
    "primaryPersonId": "p1",          // Person being evaluated
    "people": [{                      // Household members
      "id": "p1",
      "dateOfBirth": "1950-01-01"
    }],
    "enrollments": [{                 // Current benefits
      "personId": "p1",
      "benefit": "homestead"
    }],
    "simpleChecks": {                 // Boolean flags
      "ownerOccupant": true,
      "livesInPhiladelphiaPa": true
    }
  }
}
```

### Naming Rules (CRITICAL)

1. **Decision Service naming**: Must be `{ModelName}Service`
   - Model "PersonMinAge" → Service "PersonMinAgeService"
2. **Model names must be unique** across all DMN files
3. **File names**: Use kebab-case (`person-min-age.dmn`)

Breaking these rules = endpoint won't appear.

---

## Development

### Adding a Benefit

1. Create DMN file: `src/main/resources/benefits/{jurisdiction}/{name}.dmn`
2. Import: BDT.dmn, Benefits.dmn, any check DMNs you need
3. Define Decision Service: `{BenefitName}Service`
4. Implement checks (call reusable checks or inline logic)
5. Create `checks` context and `isEligible` decision
6. Save → endpoint appears at `/api/v1/benefits/{jurisdiction}/{name}`

The builder discovers a benefit's composition through the generated OpenAPI
operation. Keep each named entry in the `checks` context as a Decision Service
invocation. Literal fields in its `parameters` binding become fixed parameters;
a direct expression such as `situation.primaryPersonId` becomes a runtime
parameter binding. More complex expressions should be encapsulated in a check
under `checks/internal/`, which remains available to imported benefits but is
not shown in the general check picker. The generated operation publishes this
composition as `x-bdt-benefit`.

Example benefit response:
```json
{
  "situation": {...},
  "checks": {
    "age65Plus": true,
    "ownerOccupant": true,
    "livesInPhiladelphia": false
  },
  "isEligible": false
}
```

### Creating a Check

1. Create DMN: `src/main/resources/checks/{category}/{name}.dmn`
2. Import BDT.dmn (and relevant base modules)
3. Define Decision Service: `{CheckName}Service`
4. Input: `situation` + `parameters`, Output: `result` (boolean)
5. Save → endpoint appears at `/api/v1/checks/{category}/{name}`

### Hot Reload

DMN changes are picked up automatically:
1. Edit DMN file in VS Code ([DMN Editor extension](https://marketplace.visualstudio.com/items?itemName=kie-group.dmn-vscode-extension))
2. Save
3. Check Swagger UI—endpoint updates immediately

Java changes require restart.

### Debugging

- **Endpoint missing?** Check Decision Service name matches `{ModelName}Service` pattern
- **Evaluation errors?** Check Quarkus logs (shows DMN evaluation details)
- **Import issues?** Use namespace-qualified references: `ImportedModel.ServiceName(...)`

Test individual decisions in Swagger UI before composing them.

---

## Testing

### Java Tests (`mvn test`)

Purpose: Validate internal behavior (model discovery, endpoint patterns)
- NOT for testing DMN business logic
- Location: `src/test/java/org/codeforphilly/bdt/api/`

### Bruno API Tests (`cd test/bdt && bru run`)

Purpose: Validate DMN logic and API behavior
- Test structure mirrors DMN structure
- Location: `test/bdt/benefits/`, `test/bdt/checks/`
- Create Bruno tests for every new benefit/check

**Workflow**: Write Bruno tests first (TDD), then implement DMN.

---

## Deployment

### Creating a Release

```bash
# Update version in pom.xml and create git tag atomically
cd library-api
./bin/tag-release 0.4.0

# Review changes
git show

# Push to trigger deployment
git push origin your-branch
git push origin library-api-v0.4.0
```

Pushing the tag triggers GitHub Actions → Docker build → Google Cloud Run deployment.

**Semantic versioning**:
- MAJOR: Breaking changes (removing endpoints, changing response format)
- MINOR: New features (new benefits/checks)
- PATCH: Bug fixes

**Production**: Google Cloud Run, `us-central1`, public API

---

## Reference

### Technology Stack

| Technology | Version | Purpose |
|------------|---------|---------|
| Java | 17 | Runtime |
| Quarkus | 2.16.12.Final | Framework |
| Kogito | 1.44.1.Final | DMN engine |
| Maven | 3.8+ | Build tool |
| DMN | 1.3 | Decision modeling |
| Bruno | Latest | API testing |

### Key Commands

```bash
# Development
bin/dev                   # Start dev server
mvn clean compile         # Clean rebuild
mvn clean package         # Build JAR

# Testing
mvn test                  # Java tests
cd test/bdt && bru run    # Bruno tests

# Deployment
./bin/tag-release 0.4.0   # Create release
```

### Important Constraints

1. All DMN model names must be globally unique
2. Decision Services must be named `{ModelName}Service`
3. Java 17 required (not 21 like main BDT project)
4. Hot reload works for DMN only; Java changes need restart
5. Imported decision services can't share names

### Configuration

- **Port**: Set `QUARKUS_HTTP_PORT` (default: 8083)

### Key Files

- `src/main/resources/BDT.dmn` - Root model with shared types
- `src/main/resources/checks/` - Reusable eligibility checks
- `src/main/resources/benefits/` - Specific benefit programs
- `src/main/java/org/codeforphilly/bdt/api/` - Custom REST endpoints
- `test/bdt/` - Bruno API tests

### Resources

- **Learn DMN**: https://learn-dmn-in-15-minutes.com/
- **Kogito Docs**: https://kogito.kie.org/
- **Quarkus Guides**: https://quarkus.io/guides/
- **CLAUDE.md**: Detailed architecture and AI assistant guidance
- **GitHub**: https://github.com/codeforphilly/benefit-decision-toolkit

---

## Architecture Notes

### Custom vs Kogito Defaults

This project uses **custom endpoint generation** (not Kogito's defaults):
- `DynamicDMNResource.java` handles all requests via `POST /api/v1/{path}`
- `ModelRegistry.java` discovers DMN models at startup
- `DynamicDMNOpenAPIFilter.java` generates OpenAPI docs

**Why?** Better URL patterns (match file structure), consistent response format, custom OpenAPI.

### Code You Can Edit

- `src/main/java/org/codeforphilly/bdt/api/` - REST + OpenAPI
- `src/main/java/org/codeforphilly/bdt/functions/` - Custom FEEL functions
- `src/main/resources/*.dmn` - DMN decision models

**DO NOT EDIT**: `target/generated-sources/kogito/` (auto-generated)

### Advanced Features

- **DecisionServiceInvoker**: Programmatic DMN invocation (Java only, not from FEEL)
- **Custom OpenAPI**: Type schemas auto-generated from DMN types

See **CLAUDE.md** for detailed architecture, troubleshooting, and development patterns.

---

**Version**: 0.4.0 | **Last Updated**: 2025-01-21
