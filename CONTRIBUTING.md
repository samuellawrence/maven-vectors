# Contributing to Maven Vectors

Thank you for your interest in contributing to Maven Vectors! This document provides guidelines and information for contributors.

## 🚀 Getting Started

### Prerequisites

- Java 17+
- Maven 3.9+
- Git

### Clone and Build

```bash
git clone https://github.com/maven-vectors/maven-vectors.git
cd maven-vectors
mvn clean install
```

### Run Tests

```bash
mvn test
```

## 📋 How to Contribute

### Reporting Bugs

1. Check existing issues to avoid duplicates
2. Use the bug report template
3. Include:
   - Clear description of the issue
   - Steps to reproduce
   - Expected vs actual behavior
   - Environment details (Java version, OS, etc.)

### Suggesting Features

1. Check existing issues and discussions
2. Open a new discussion for feature ideas
3. Provide use cases and examples
4. Be open to feedback and iteration

### Submitting Pull Requests

1. **Fork** the repository
2. **Create a branch** from `main`:
   ```bash
   git checkout -b feature/my-feature
   ```
3. **Make your changes** following our coding standards
4. **Add tests** for new functionality
5. **Update documentation** if needed
6. **Run the test suite**:
   ```bash
   mvn test
   ```
7. **Commit** with a clear message:
   ```bash
   git commit -m "feat: add support for Kotlin files"
   ```
8. **Push** to your fork
9. **Open a Pull Request**

## 📝 Coding Standards

### Java Style

- Follow [Google Java Style Guide](https://google.github.io/styleguide/javaguide.html)
- Use meaningful variable and method names
- Maximum line length: 120 characters
- Use records for immutable data classes (Java 17+)

### Documentation

- All public classes and methods must have Javadoc
- Include code examples in complex API documentation
- Update README.md for user-facing changes

### Testing

- Write unit tests for all new code
- Use JUnit 5
- Aim for >80% code coverage on new code
- Include integration tests for plugin functionality

### Commit Messages

Follow [Conventional Commits](https://www.conventionalcommits.org/):

```
type(scope): description

[optional body]

[optional footer]
```

Types:
- `feat`: New feature
- `fix`: Bug fix
- `docs`: Documentation only
- `style`: Code style changes
- `refactor`: Code refactoring
- `test`: Adding tests
- `chore`: Maintenance tasks

Examples:
```
feat(core): add HNSW index implementation
fix(plugin): handle missing source directory gracefully
docs(readme): add configuration examples
```

## 🏗️ Project Structure

```
maven-vectors/
├── vectors-core/           # Core library
│   └── src/
│       ├── main/java/io/maven/vectors/
│       │   ├── VectorIndex.java      # Main interface
│       │   ├── CodeChunk.java        # Data model
│       │   └── parser/               # Java parsing
│       └── test/java/
├── vectors-embeddings/     # Embedding models
│   └── src/main/java/io/maven/vectors/embeddings/
├── vectors-maven-plugin/   # Maven plugin
│   └── src/main/java/io/maven/vectors/plugin/
├── vectors-cli/            # CLI tool
└── examples/               # Example projects
```

## 🔍 Areas for Contribution

### Good First Issues

Look for issues labeled `good first issue`:
- Documentation improvements
- Bug fixes with clear reproduction steps
- Test coverage improvements

### Wanted Features

- **Gradle Plugin** — Port Maven plugin to Gradle
- **IDE Plugins** — IntelliJ, VS Code integration
- **Additional Models** — Support for more embedding models
- **Performance** — HNSW optimization, GPU support
- **Languages** — Kotlin, Scala support

## 🧪 Testing Locally

### Unit Tests

```bash
mvn test -pl vectors-core
```

### Integration Tests

```bash
mvn verify -pl vectors-maven-plugin
```

### Manual Testing

```bash
# Build the plugin
mvn install -DskipTests

# Test on example project
cd examples/sample-project
mvn vectors:generate
mvn vectors:query -Dvectors.query="test"
```

## 📚 Resources

- [JavaParser Documentation](https://javaparser.org/)
- [JVector GitHub](https://github.com/jbellis/jvector)
- [ONNX Runtime Java](https://onnxruntime.ai/docs/get-started/with-java.html)
- [Maven Plugin Development](https://maven.apache.org/plugin-developers/)

## 📜 License

By contributing, you agree that your contributions will be licensed under the Apache License 2.0.

## 💬 Communication

- **GitHub Issues** — Bug reports, feature requests
- **GitHub Discussions** — Questions, ideas, general discussion
- **Pull Requests** — Code contributions

## 🙏 Thank You!

Every contribution matters, whether it's:
- 🐛 Reporting a bug
- 💡 Suggesting a feature
- 📝 Improving documentation
- 🔧 Submitting code

We appreciate your help in making Maven Vectors better!
