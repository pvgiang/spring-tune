package io.github.pvgiang.springtune.check.rules;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Syntactic helpers for source rules (no symbol resolution: names are matched textually). */
final class Sources {

    private Sources() {
    }

    /** {@code true} for {@code Name} or {@code a.b.Name} scopes. */
    static boolean isTypeReference(Expression scope, String simpleName) {
        if (scope instanceof NameExpr name) {
            return name.getNameAsString().equals(simpleName);
        }
        if (scope instanceof FieldAccessExpr field) {
            return field.getNameAsString().equals(simpleName);
        }
        return false;
    }

    /** Method names called along a fluent chain ending at {@code last}, innermost first. */
    static List<MethodCallExpr> chain(MethodCallExpr last) {
        List<MethodCallExpr> calls = new ArrayList<>();
        Expression current = last;
        while (current instanceof MethodCallExpr call) {
            calls.add(0, call);
            current = call.getScope().orElse(null);
        }
        return calls;
    }

    /** "Type#method" label for the enclosing member, used as a stable finding subject. */
    static String owner(Node node) {
        String type = node.findAncestor(TypeDeclaration.class).map(t -> t.getNameAsString()).orElse("?");
        Optional<CallableDeclaration> callable = node.findAncestor(CallableDeclaration.class);
        return type + callable.map(c -> "#" + c.getNameAsString()).orElse("");
    }

    /** Body to search for configuration calls: enclosing method/constructor, else the enclosing class. */
    static Node scopeOf(Node node) {
        Optional<CallableDeclaration> callable = node.findAncestor(CallableDeclaration.class);
        if (callable.isPresent()) {
            return callable.get();
        }
        return node.findAncestor(ClassOrInterfaceDeclaration.class).map(Node.class::cast).orElse(node);
    }

    static boolean callsAny(Node scope, java.util.Set<String> methodNames) {
        return scope.findFirst(MethodCallExpr.class, c -> methodNames.contains(c.getNameAsString())).isPresent();
    }

    static boolean isBeanMethod(MethodDeclaration method) {
        return method.getAnnotationByName("Bean").isPresent();
    }
}
