package io.github.winky.springtune.check.project;

import io.github.winky.springtune.check.PathUtils;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Reads dependency artifact ids and the Spring Boot version from Maven and Gradle build files without
 * running the build. Ancestor directories up to the analysis root are included to approximate parent
 * POM / root build script inheritance.
 */
public final class BuildInspector {

    private static final Pattern GRADLE_COORDINATE = Pattern.compile("[\"']([\\w.\\-]+):([\\w.\\-]+)(?::[^\"']*)?[\"']");
    private static final Pattern GRADLE_BOOT_PLUGIN = Pattern.compile(
            "id\\s*\\(?\\s*[\"']org\\.springframework\\.boot[\"']\\s*\\)?\\s*version\\s*\\(?\\s*[\"']([^\"']+)[\"']");
    private static final Pattern GRADLE_BOOT_COORDINATE =
            Pattern.compile("org\\.springframework\\.boot:spring-boot-(?:gradle-plugin|dependencies):([0-9][^\"'\\s)]*)");
    private static final Pattern GRADLE_PROJECT = Pattern.compile("project\\(\\s*(?:path\\s*[:=]\\s*)?[\"']([^\"']+)[\"']");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}]+)}");

    public BuildInfo inspect(Path root, Path moduleDir) {
        Path buildFile = buildFile(moduleDir);
        Set<String> artifacts = new LinkedHashSet<>();
        String bootVersion = null;
        Path normalizedRoot = root.toAbsolutePath().normalize();
        for (Path dir = moduleDir.toAbsolutePath().normalize(); dir != null && dir.startsWith(normalizedRoot);
                dir = dir.getParent()) {
            Path file = buildFile(dir);
            if (file != null) {
                Facts facts = file.getFileName().toString().equals("pom.xml") ? readPom(file) : readGradle(file);
                artifacts.addAll(facts.artifacts());
                if (bootVersion == null) {
                    bootVersion = facts.bootVersion();
                }
            }
        }
        String artifactId = null;
        if (buildFile != null) {
            artifactId = buildFile.getFileName().toString().equals("pom.xml") ? pomArtifactId(buildFile)
                    : moduleDir.toAbsolutePath().normalize().getFileName().toString();
        }
        return new BuildInfo(buildFile != null ? PathUtils.relative(root, buildFile) : null, artifactId, bootVersion,
                artifacts);
    }

    static Path buildFile(Path dir) {
        for (String name : new String[] {"pom.xml", "build.gradle.kts", "build.gradle"}) {
            Path candidate = dir.resolve(name);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private record Facts(Set<String> artifacts, String bootVersion) {
    }

    private static Facts readPom(Path pom) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setExpandEntityReferences(false);
            Document document = factory.newDocumentBuilder().parse(pom.toFile());
            Element project = document.getDocumentElement();
            Map<String, String> properties = new HashMap<>();
            Element props = child(project, "properties");
            if (props != null) {
                NodeList list = props.getChildNodes();
                for (int i = 0; i < list.getLength(); i++) {
                    if (list.item(i) instanceof Element element) {
                        properties.put(element.getTagName(), element.getTextContent().trim());
                    }
                }
            }
            Set<String> artifacts = new LinkedHashSet<>();
            // Only <project><dependencies> declare usage; dependencyManagement merely pins versions.
            for (Element dependency : dependencies(child(project, "dependencies"))) {
                String artifactId = text(dependency, "artifactId");
                if (artifactId != null) {
                    artifacts.add(artifactId);
                }
            }
            String bootVersion = null;
            Element management = child(project, "dependencyManagement");
            for (Element dependency : dependencies(management == null ? null : child(management, "dependencies"))) {
                if ("spring-boot-dependencies".equals(text(dependency, "artifactId")) && bootVersion == null) {
                    bootVersion = resolve(text(dependency, "version"), properties);
                }
            }
            Element parent = child(project, "parent");
            if (parent != null && "spring-boot-starter-parent".equals(text(parent, "artifactId"))) {
                bootVersion = text(parent, "version");
            }
            if (bootVersion == null) {
                bootVersion = properties.get("spring-boot.version");
            }
            return new Facts(artifacts, bootVersion);
        } catch (Exception ex) {
            return new Facts(Set.of(), null);
        }
    }

    private static String pomArtifactId(Path pom) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            return text(factory.newDocumentBuilder().parse(pom.toFile()).getDocumentElement(), "artifactId");
        } catch (Exception ex) {
            return null;
        }
    }

    private static Facts readGradle(Path file) {
        try {
            String content = Files.readString(file);
            Set<String> artifacts = new LinkedHashSet<>();
            Matcher coordinates = GRADLE_COORDINATE.matcher(content);
            while (coordinates.find()) {
                artifacts.add(coordinates.group(2));
            }
            Matcher projects = GRADLE_PROJECT.matcher(content);
            while (projects.find()) {
                String path = projects.group(1);
                artifacts.add(path.substring(path.lastIndexOf(':') + 1));
            }
            String bootVersion = null;
            Matcher plugin = GRADLE_BOOT_PLUGIN.matcher(content);
            if (plugin.find()) {
                bootVersion = plugin.group(1);
            } else {
                Matcher coordinate = GRADLE_BOOT_COORDINATE.matcher(content);
                if (coordinate.find()) {
                    bootVersion = coordinate.group(1);
                }
            }
            return new Facts(artifacts, bootVersion);
        } catch (IOException ex) {
            return new Facts(Set.of(), null);
        }
    }

    private static String resolve(String value, Map<String, String> properties) {
        if (value == null) {
            return null;
        }
        Matcher matcher = PLACEHOLDER.matcher(value);
        if (matcher.matches()) {
            return properties.get(matcher.group(1));
        }
        return value;
    }

    private static java.util.List<Element> dependencies(Element container) {
        java.util.List<Element> result = new java.util.ArrayList<>();
        if (container != null) {
            NodeList children = container.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                if (children.item(i) instanceof Element element && element.getTagName().equals("dependency")) {
                    result.add(element);
                }
            }
        }
        return result;
    }

    private static Element child(Element parent, String name) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node instanceof Element element && element.getTagName().equals(name)) {
                return element;
            }
        }
        return null;
    }

    private static String text(Element parent, String name) {
        Element element = child(parent, name);
        return element == null ? null : element.getTextContent().trim();
    }
}
