# Build System Guide

This repo uses **two build systems in parallel** — intentionally.

| Tool | Purpose | When to use |
|------|---------|-------------|
| **Bazel** | Canonical build + CI, multi-language graph | `bazel build/test`, CI, cross-language deps |
| **Maven** | IntelliJ IDE support for Java services only | IDE only — never for CI or deployment |

They coexist without conflict: Bazel ignores `pom.xml`, Maven ignores `BUILD.bazel`.

---

## Why both?

**Bazel** is the source of truth for builds because this is a multi-language monorepo:
- Java (Spring Boot services, LeetCode challenges)
- Python (ML Platform — FastAPI, Triton, Qdrant)
- Go (future CLIs / experiments)
- C++ (future low-latency / systems work)
- Rust (future systems work)

Maven/Gradle cannot build Go, Rust, or C++. Bazel builds all languages with one command
(`bazel build //...`) and enforces the dependency graph across language boundaries.

**Maven** exists solely because IntelliJ does not provide Java auto-completion, source/test/
resource root recognition, or Spring plugin support without a Maven or Gradle descriptor.
The `pom.xml` files carry no build logic — they are dependency manifests for the IDE.

---

## File conventions

### Java services (`apps/uber/*`)

Every Java service has **both** files side-by-side:

```
apps/uber/eats-service/
  BUILD.bazel   ← Bazel: used for actual builds, CI, cross-service deps
  pom.xml       ← Maven: used by IntelliJ only for classpath + code insight
  src/
    main/
      java/       ← source root (both Bazel and Maven point here)
      resources/  ← resources root
    test/
      java/       ← test root
```

**Keep deps in sync.** When you add a dependency:
1. Add it to `BUILD.bazel` deps list (Bazel)
2. Add the matching `<dependency>` to `pom.xml` (Maven/IDE)

### Non-Java projects (Go, Python, Rust, C++)

Only `BUILD.bazel` — no `pom.xml`. IntelliJ language plugins (Go plugin, Python plugin)
get their project roots from `.idea/` module config or their own project files, not Maven.

### LeetCode challenges (`challenges/leetcode/*`)

Only `BUILD.bazel` + `challenges/challenges.iml`. No Maven needed — pure Java stdlib,
no external deps. IntelliJ loads them via the `.iml` module directly.

---

## Adding a new Java service

```bash
# 1. Create the service directory
mkdir -p apps/uber/my-service/src/main/java/com/uber/myservice
mkdir -p apps/uber/my-service/src/main/resources
mkdir -p apps/uber/my-service/src/test/java/com/uber/myservice
```

**`apps/uber/my-service/BUILD.bazel`** — Bazel build target:
```python
load("@rules_java//java:defs.bzl", "java_binary", "java_library")

java_library(
    name = "my_service_lib",
    srcs = glob(["src/main/java/**/*.java"]),
    resources = glob(["src/main/resources/**"]),
    deps = [
        "@maven//:org_springframework_boot_spring_boot_starter_web",
        # ... other deps
    ],
)

java_binary(
    name = "my-service",
    main_class = "com.uber.myservice.MyServiceApplication",
    runtime_deps = [":my_service_lib"],
)
```

**`apps/uber/my-service/pom.xml`** — IDE classpath (inherit from parent):
```xml
<project>
  <parent>
    <groupId>com.uber</groupId>
    <artifactId>uber-backend</artifactId>
    <version>0.0.1-SNAPSHOT</version>
    <relativePath>../pom.xml</relativePath>
  </parent>
  <artifactId>my-service</artifactId>
  <dependencies>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
  </dependencies>
</project>
```

**`apps/uber/pom.xml`** — register the new module:
```xml
<modules>
  <module>eats-service</module>
  <module>my-service</module>   <!-- add this line -->
</modules>
```

Then in IntelliJ: **Maven panel → Reload All Maven Projects**.

---

## Adding a non-Java project

Only create `BUILD.bazel` — no `pom.xml`. Register any new language rules in `MODULE.bazel`
if not already present. See `CLAUDE.md` for Bazel language rules already configured.

---

## CI / deployment

**Always use Bazel.** Never use `mvn` in CI or deployment scripts.

```bash
bazel build //apps/uber/eats-service:eats-service   # build JAR
bazel test //challenges/...                          # run all challenge tests
bazel build //...                                    # build everything, all languages
```

---

## Maven parent structure

```
apps/uber/pom.xml                    ← parent BOM (Spring Boot 3.2.3, AWS SDK versions)
apps/uber/eats-service/pom.xml       ← IDE module, inherits parent
apps/uber/<future-service>/pom.xml   ← IDE module, inherits parent
```

The parent `pom.xml` manages shared dependency versions via `<dependencyManagement>`.
Individual service poms declare deps without versions — inherited from the parent.

---

## IntelliJ setup checklist

After cloning or after deleting `.idea/`:
1. Open the repo root in IntelliJ
2. **File → Project Structure → SDKs** — ensure JDK 26 is registered
3. Maven notification appears: **Load Maven Projects**
4. `challenges` and `shared` modules load automatically from their `.iml` files
5. Verify: `src/main/java` folders show as blue (source), `resources` as yellow
