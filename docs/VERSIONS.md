# Verified dependencies

Checked 2026-11-07 against official documentation and Maven Central artifact metadata.

| Component | Selection | Evidence / compatibility |
| --- | --- | --- |
| Java | 21 | [Spring Boot system requirements](https://docs.spring.io/spring-boot/system-requirements.html) list Java 17-27 for Boot 4.1.2. Installed test JDK is 21.0.1; use a maintained Java 21 patch release for daily use. |
| Spring Boot | 4.1.2 stable | Same official page; release POM resolved from Maven Central. Its BOM manages Spring, Hibernate, Jackson, Flyway, JDBC driver, JUnit, Mockito, Micrometer. |
| Maven | 3.9.11 via Wrapper 3.3.4 | Boot requires Maven >=3.6.3. [Apache Maven Wrapper](https://maven.apache.org/tools/wrapper/). Standard Apache generated scripts, not a custom launcher. |
| springdoc | 3.2.0 | [Official compatibility matrix, section 13.80](https://springdoc.org/#what-is-the-compatibility-matrix-of-springdoc-openapi-with-spring-boot) pairs Boot 4.1.x with springdoc 3.1.x-3.2.x. |
| PostgreSQL | 18; tests 18.6 | [PostgreSQL documentation](https://www.postgresql.org/docs/18/). Uses PostgreSQL locking semantics and real migrations. |
| Test PostgreSQL launcher | Zonky 2.2.2, binaries BOM 18.6.0 | [Project instructions](https://github.com/zonkyio/embedded-postgres) document native Windows/Linux/macOS operation and selecting binaries via BOM. Test scope only. |

Boot 4 uses Jackson 3 (`tools.jackson`); no Jackson 2 databind override. No independently pinned Spring Security or Hibernate version. OpenAPI generation is covered by an integration test. First Maven run requires internet for free public artifacts; subsequent cached builds and application execution need no external APIs.

Update the Boot parent as a unit, recheck springdoc's matrix, then run `verify`; do not upgrade these libraries independently without compatibility tests.
