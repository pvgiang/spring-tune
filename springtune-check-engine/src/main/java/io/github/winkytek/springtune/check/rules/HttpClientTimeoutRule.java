package io.github.winkytek.springtune.check.rules;

import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import io.github.winkytek.springtune.check.project.ModuleModel;
import io.github.winkytek.springtune.check.source.JavaSources;
import io.github.winkytek.springtune.model.Finding;
import io.github.winkytek.springtune.model.Severity;
import java.util.List;
import java.util.Set;

/**
 * ST-HTTP-001: Spring HTTP clients created in code without timeouts. Only constructions where Spring
 * Boot's {@code spring.http.client(s).*} properties cannot apply are reported (static factories and
 * {@code new}); injected, auto-configured builders are not, because their timeouts may come from properties.
 */
public final class HttpClientTimeoutRule extends ModuleRule {

    private static final Set<String> REST_CLIENT_CONFIGURERS = Set.of("requestFactory", "apply");
    private static final Set<String> WEB_CLIENT_CONFIGURERS = Set.of("clientConnector", "apply", "exchangeFunction");
    private static final Set<String> REST_TEMPLATE_BUILDER_CONFIGURERS = Set.of("connectTimeout", "readTimeout",
            "setConnectTimeout", "setReadTimeout", "requestFactory", "requestFactorySettings",
            "requestFactoryBuilder");
    private static final String WHY = "A call without a read timeout waits as long as the remote side keeps the "
            + "socket open. When a dependency hangs, request threads pile up on it until the service stops "
            + "responding: one slow dependency becomes a full outage.";

    public HttpClientTimeoutRule() {
        super("ST-HTTP-001", "HTTP client without timeouts", Severity.WARNING, "http",
                "RestTemplate, RestClient or WebClient constructed in code without a request factory/connector "
                        + "that sets connect and read timeouts.");
    }

    @Override
    protected void evaluate(ModuleModel module, RuleOptions options, List<Finding> findings) {
        for (JavaSources.ParsedSource source : module.sources().all()) {
            for (ObjectCreationExpr creation : source.unit().findAll(ObjectCreationExpr.class)) {
                if (creation.getType().getNameAsString().equals("RestTemplate") && creation.getArguments().isEmpty()) {
                    findings.add(finding(module, Sources.owner(creation) + ":new RestTemplate()",
                            "new RestTemplate() uses SimpleClientHttpRequestFactory with no connect or read timeout"
                                    + " (infinite).",
                            WHY, "Build it with RestTemplateBuilder.connectTimeout(...).readTimeout(...), or pass a "
                                    + "request factory with timeouts set.",
                            source.locationOf(creation)));
                }
            }
            for (MethodCallExpr call : source.unit().findAll(MethodCallExpr.class)) {
                inspectStaticFactory(module, source, call, findings);
                if (call.getNameAsString().equals("build")) {
                    inspectBuilderChain(module, source, call, findings);
                }
            }
        }
    }

    private void inspectStaticFactory(ModuleModel module, JavaSources.ParsedSource source, MethodCallExpr call,
            List<Finding> findings) {
        if (!call.getNameAsString().equals("create") || call.getScope().isEmpty()) {
            return;
        }
        Expression scope = call.getScope().get();
        if (Sources.isTypeReference(scope, "WebClient")) {
            findings.add(finding(module, Sources.owner(call) + ":WebClient.create",
                    "WebClient.create(...) uses the default connector: no response timeout (Reactor Netty).",
                    WHY, "Use WebClient.builder().clientConnector(new ReactorClientHttpConnector(HttpClient.create()"
                            + ".responseTimeout(...).option(ChannelOption.CONNECT_TIMEOUT_MILLIS, ...))), or inject the "
                            + "auto-configured WebClient.Builder.",
                    source.locationOf(call)));
        } else if (Sources.isTypeReference(scope, "RestClient") && isUrlOrNothing(call)) {
            findings.add(finding(module, Sources.owner(call) + ":RestClient.create",
                    "RestClient.create(...) has no explicit timeouts. Depending on the HTTP library on the classpath, "
                            + "the read timeout is infinite (JDK HttpClient, HttpURLConnection) or a library default "
                            + "of minutes.",
                    WHY, "Use RestClient.builder().requestFactory(factory) with connect and read timeouts set, or inject "
                            + "the auto-configured RestClient.Builder and set spring.http.client(s).read-timeout.",
                    source.locationOf(call)));
        }
    }

    private void inspectBuilderChain(ModuleModel module, JavaSources.ParsedSource source, MethodCallExpr build,
            List<Finding> findings) {
        List<MethodCallExpr> chain = Sources.chain(build);
        MethodCallExpr first = chain.get(0);
        Set<String> called = new java.util.HashSet<>();
        chain.forEach(c -> called.add(c.getNameAsString()));
        Expression rootScope = first.getScope().orElse(null);

        if (first.getNameAsString().equals("builder") && first.getArguments().isEmpty() && rootScope != null) {
            if (Sources.isTypeReference(rootScope, "RestClient")
                    && called.stream().noneMatch(REST_CLIENT_CONFIGURERS::contains)) {
                findings.add(finding(module, Sources.owner(build) + ":RestClient.builder",
                        "RestClient.builder()...build() sets no request factory, so no explicit timeouts apply "
                                + "(Spring Boot properties only configure the injected builder).",
                        WHY, "Add .requestFactory(factory) with connect and read timeouts, or inject the "
                                + "auto-configured RestClient.Builder.",
                        source.locationOf(build)));
            } else if (Sources.isTypeReference(rootScope, "WebClient")
                    && called.stream().noneMatch(WEB_CLIENT_CONFIGURERS::contains)) {
                findings.add(finding(module, Sources.owner(build) + ":WebClient.builder",
                        "WebClient.builder()...build() sets no client connector: no response timeout (Reactor Netty).",
                        WHY, "Add .clientConnector(new ReactorClientHttpConnector(HttpClient.create()"
                                + ".responseTimeout(...))).",
                        source.locationOf(build)));
            }
        } else if (rootScope instanceof ObjectCreationExpr creation
                && creation.getType().getNameAsString().equals("RestTemplateBuilder")
                && called.stream().noneMatch(REST_TEMPLATE_BUILDER_CONFIGURERS::contains)) {
            findings.add(finding(module, Sources.owner(build) + ":new RestTemplateBuilder",
                    "new RestTemplateBuilder()...build() sets no connect or read timeout.",
                    WHY, "Add .connectTimeout(...) and .readTimeout(...), or inject the auto-configured "
                            + "RestTemplateBuilder.",
                    source.locationOf(build)));
        }
    }

    /** {@code RestClient.create()} or {@code create("http://...")}: never configures a request factory. */
    private static boolean isUrlOrNothing(MethodCallExpr call) {
        if (call.getArguments().isEmpty()) {
            return true;
        }
        if (call.getArguments().size() != 1) {
            return false;
        }
        Expression argument = call.getArgument(0);
        return argument instanceof StringLiteralExpr
                || (argument instanceof MethodCallExpr uri && uri.getNameAsString().equals("create")
                        && uri.getScope().filter(s -> Sources.isTypeReference(s, "URI")).isPresent());
    }
}
