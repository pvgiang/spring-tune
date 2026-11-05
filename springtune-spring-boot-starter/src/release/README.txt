springtune-spring-boot-starter has no code of its own: it only depends on
springtune-spring-boot-autoconfigure and spring-boot-starter-actuator.

Maven Central requires -sources and -javadoc jars for every jar artifact, so
this placeholder is published as both. Sources and Javadoc of the runtime
are in io.github.winkytek.springtune:springtune-spring-boot-autoconfigure.
