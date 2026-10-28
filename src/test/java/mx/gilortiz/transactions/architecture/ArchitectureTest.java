package mx.gilortiz.transactions.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.EvaluationResult;
import mx.gilortiz.transactions.architecture.fixture.FieldInjectedBean;
import mx.gilortiz.transactions.architecture.fixture.provider.http.HttpAdapterFixture;
import mx.gilortiz.transactions.architecture.fixture.transaction.DomainDependingOnHttpFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ArchitectureTest {

    private static final String ROOT_PACKAGE = "mx.gilortiz.transactions";

    // allowEmptyShould: el paquete provider.http y los beans aún pueden no existir en las primeras features.
    static final ArchRule DOMAIN_DOES_NOT_DEPEND_ON_PROVIDER_HTTP = noClasses()
            .that().resideInAPackage("..transaction..")
            .should().dependOnClassesThat().resideInAPackage("..provider.http..")
            .allowEmptyShould(true);

    static final ArchRule NO_FIELD_INJECTION = noFields()
            .should().beAnnotatedWith(Autowired.class)
            .allowEmptyShould(true);

    private static final JavaClasses PRODUCTION_CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages(ROOT_PACKAGE);

    @Test
    @DisplayName("el dominio (transaction) no depende del adaptador provider.http")
    void domainDoesNotDependOnProviderHttp() {
        DOMAIN_DOES_NOT_DEPEND_ON_PROVIDER_HTTP.check(PRODUCTION_CLASSES);
    }

    @Test
    @DisplayName("ningún campo de producción se inyecta con @Autowired")
    void noFieldInjection() {
        NO_FIELD_INJECTION.check(PRODUCTION_CLASSES);
    }

    @Test
    @DisplayName("la regla de dominio detecta una clase de transaction que usa provider.http")
    void domainRuleDetectsViolation() {
        JavaClasses fixtures = new ClassFileImporter()
                .importClasses(DomainDependingOnHttpFixture.class, HttpAdapterFixture.class);

        EvaluationResult result = DOMAIN_DOES_NOT_DEPEND_ON_PROVIDER_HTTP.evaluate(fixtures);

        assertThat(result.hasViolation()).isTrue();
        assertThat(result.getFailureReport().getDetails())
                .anyMatch(detail -> detail.contains(HttpAdapterFixture.class.getName()));
    }

    @Test
    @DisplayName("la regla de inyección detecta un campo con @Autowired")
    void fieldInjectionRuleDetectsViolation() {
        JavaClasses fixtures = new ClassFileImporter().importClasses(FieldInjectedBean.class);

        EvaluationResult result = NO_FIELD_INJECTION.evaluate(fixtures);

        assertThat(result.hasViolation()).isTrue();
        assertThat(result.getFailureReport().getDetails())
                .anyMatch(detail -> detail.contains(FieldInjectedBean.class.getName()));
    }
}
