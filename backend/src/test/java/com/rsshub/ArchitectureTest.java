package com.rsshub;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideOutsideOfPackages;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * The three boundary rules from doc/design/02-architecture.md §3, plus two that fall out of them. They exist
 * because a modular monolith only stays modular when violating it is a build failure, not a review comment.
 * <p>
 * Rules over packages M0 has not created yet (feedparse, feedrender) carry {@code allowEmptyShould}: they are
 * forward guards, and ArchUnit otherwise fails a rule whose {@code that()} clause matches nothing. Once those
 * packages exist the flag suppresses nothing, because the rules then do have classes to judge.
 */
@AnalyzeClasses(packages = "com.rsshub", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule parsing_does_not_reach_rendering = noClasses()
        .that().resideInAPackage("com.rsshub.feedparse..")
        .should().dependOnClassesThat().resideInAPackage("com.rsshub.feedrender..")
        .because("02 §3 rule 1: NormalizedEntry is a parse output, FeedSource is a render input; sharing models "
            + "leaks internal shapes into the public feed format")
        .allowEmptyShould(true);

    @ArchTest
    static final ArchRule rendering_does_not_reach_parsing = noClasses()
        .that().resideInAPackage("com.rsshub.feedrender..")
        .should().dependOnClassesThat().resideInAPackage("com.rsshub.feedparse..")
        .because("02 §3 rule 1, the other direction")
        .allowEmptyShould(true);

    @ArchTest
    static final ArchRule only_designated_packages_reach_the_network = noClasses()
        .that().resideOutsideOfPackages(
            "com.rsshub.feedfetch..", "com.rsshub.notify..", "com.rsshub.ai..", "com.rsshub.security..")
        .should().dependOnClassesThat()
        .resideInAnyPackage("java.net.http..", "org.apache.hc..", "okhttp3..")
        .because("02 §3 rule 2: SSRF defence and per-host politeness live in one place, so a second HTTP client "
            + "somewhere else is a second, unguarded egress path");

    @ArchTest
    static final ArchRule rendering_never_touches_the_store = noClasses()
        .that().resideInAPackage("com.rsshub.feedrender..")
        .should().dependOnClassesThat().resideInAPackage("com.rsshub.feedstore..")
        .because("02 §3 rule 3: a GET on /feed/* must have zero write side effects, which readers hammer all day")
        .allowEmptyShould(true);

    @ArchTest
    static final ArchRule common_is_the_bottom_layer = classes()
        .that().resideInAPackage("com.rsshub.common..")
        .should().onlyDependOnClassesThat(
            resideOutsideOfPackages("com.rsshub..").or(resideInAPackage("com.rsshub.common..")))
        .because("common holds errors, ids and clocks; if it can reach a domain package it stops being shared "
            + "infrastructure and becomes a cycle hub");

    @ArchTest
    static final ArchRule admin_api_is_a_leaf = noClasses()
        .that().resideOutsideOfPackage("com.rsshub.adminapi..")
        .should().dependOnClassesThat().resideInAPackage("com.rsshub.adminapi..")
        .because("the REST layer is a transport, and anything that depends on a transport cannot be reused "
            + "by another one");

    @ArchTest
    static final ArchRule no_package_cycles = slices()
        .matching("com.rsshub.(*)..")
        .should().beFreeOfCycles();
}
