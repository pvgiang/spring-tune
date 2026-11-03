package io.github.winky.springtune.check.deploy;

/**
 * @param maxReplicas maximum number of instances that may run at once
 * @param source      where the number comes from, for the explanation text
 */
public record ReplicaInfo(int maxReplicas, String source) {
}
