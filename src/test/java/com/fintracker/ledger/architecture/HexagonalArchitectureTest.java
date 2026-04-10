package com.fintracker.ledger.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * ArchUnit tests that programmatically enforce the Hexagonal Architecture boundary.
 * These tests fail during CI if any infrastructure code leaks into the domain layer.
 */
@DisplayName("Hexagonal Architecture Boundary Tests")
class HexagonalArchitectureTest {

    private final JavaClasses classes = new ClassFileImporter()
            .importPackages("com.fintracker.ledger");

    @Test
    @DisplayName("Domain layer must not depend on Spring Web")
    void domainMustNotDependOnSpringWeb() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..domain..")
                .should().dependOnClassesThat()
                .resideInAPackage("org.springframework.web..");
        rule.check(classes);
    }

    @Test
    @DisplayName("Domain layer must not depend on jOOQ")
    void domainMustNotDependOnJooq() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..domain..")
                .should().dependOnClassesThat()
                .resideInAPackage("org.jooq..");
        rule.check(classes);
    }

    @Test
    @DisplayName("Domain layer must not depend on infrastructure")
    void domainMustNotDependOnInfrastructure() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..domain..")
                .should().dependOnClassesThat()
                .resideInAPackage("..infrastructure..");
        rule.check(classes);
    }

    @Test
    @DisplayName("API controllers must not access infrastructure adapters directly")
    void controllersMustNotAccessInfrastructureDirectly() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..api.controller..")
                .should().dependOnClassesThat()
                .resideInAPackage("..infrastructure..");
        rule.check(classes);
    }
}
