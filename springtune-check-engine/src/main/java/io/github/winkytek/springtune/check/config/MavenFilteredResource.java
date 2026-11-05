package io.github.winkytek.springtune.check.config;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import org.springframework.core.io.FileSystemResource;

/**
 * A config file as Spring would see it after Maven resource filtering, without knowing the values:
 * {@code @token@} (spring-boot-starter-parent's delimiter) becomes {@code ${token}}, an unresolvable
 * placeholder that rules then refuse to evaluate. Unfiltered {@code key: @token@} is not even valid YAML.
 * Substitution never spans lines, so line numbers in origins stay exact.
 */
final class MavenFilteredResource extends FileSystemResource {

    static final Pattern TOKEN = Pattern.compile("@([A-Za-z0-9_][A-Za-z0-9_.\\-]*)@");

    MavenFilteredResource(Path path) {
        super(path);
    }

    static boolean hasTokens(Path path) throws IOException {
        return TOKEN.matcher(Files.readString(path, StandardCharsets.UTF_8)).find();
    }

    @Override
    public InputStream getInputStream() throws IOException {
        String content = Files.readString(getFile().toPath(), StandardCharsets.UTF_8);
        return new ByteArrayInputStream(TOKEN.matcher(content).replaceAll("\\${$1}").getBytes(StandardCharsets.UTF_8));
    }
}
