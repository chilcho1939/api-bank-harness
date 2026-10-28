package mx.gilortiz.transactions.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.properties.HasAnnotations;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.EvaluationResult;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.Set;
import mx.gilortiz.transactions.architecture.fixture.FieldInjectedBean;
import mx.gilortiz.transactions.architecture.fixture.provider.http.HttpAdapterFixture;
import mx.gilortiz.transactions.architecture.fixture.transaction.DomainDependingOnHttpFixture;
import mx.gilortiz.transactions.architecture.fixture.transaction.MetaTransactionalProviderCallerFixture;
import mx.gilortiz.transactions.architecture.fixture.transaction.ProviderCallerFixture;
import mx.gilortiz.transactions.architecture.fixture.transaction.TransactionalIndirectCallerFixture;
import mx.gilortiz.transactions.architecture.fixture.transaction.TransactionalProviderCallerFixture;
import mx.gilortiz.transactions.transaction.TransactionProvider;
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

    private static final Set<String> TRANSACTIONAL_ANNOTATIONS = Set.of(
            "org.springframework.transaction.annotation.Transactional",
            "jakarta.transaction.Transactional");

    // Transitivo: cubre al servicio que usa el puerto y a quien lo invoque (controller, facade...), porque un
    // @Transactional en cualquier punto de la cadena también abarca la llamada al proveedor.
    static final DescribedPredicate<JavaClass> DEPEND_ON_PROVIDER_PORT =
            DescribedPredicate.describe("dependen directa o transitivamente del puerto TransactionProvider",
                    javaClass -> !javaClass.isEquivalentTo(TransactionProvider.class)
                            && javaClass.getTransitiveDependenciesFromSelf().stream()
                            .anyMatch(dependency -> dependency.getTargetClass()
                                    .isAssignableTo(TransactionProvider.class)));

    // C3: una transacción de BD alrededor de la llamada al proveedor retiene la conexión mientras se espera al tercero.
    static final ArchRule NO_TRANSACTIONAL_AROUND_PROVIDER = classes()
            .that(DEPEND_ON_PROVIDER_PORT)
            .should(notUseTransactional());

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
    @DisplayName("ninguna clase que depende del puerto del proveedor usa @Transactional")
    void noTransactionalAroundProvider() {
        NO_TRANSACTIONAL_AROUND_PROVIDER.check(PRODUCTION_CLASSES);
    }

    @Test
    @DisplayName("la regla de @Transactional detecta un método transaccional que llama al proveedor")
    void transactionalRuleDetectsViolation() {
        JavaClasses fixtures = new ClassFileImporter().importClasses(TransactionalProviderCallerFixture.class);

        EvaluationResult result = NO_TRANSACTIONAL_AROUND_PROVIDER.evaluate(fixtures);

        assertThat(result.hasViolation()).isTrue();
        assertThat(result.getFailureReport().getDetails())
                .anyMatch(detail -> detail.contains(TransactionalProviderCallerFixture.class.getName() + ".execute"));
    }

    @Test
    @DisplayName("la regla de @Transactional detecta una meta-anotación transaccional en la clase")
    void transactionalRuleDetectsMetaAnnotation() {
        JavaClasses fixtures = new ClassFileImporter().importClasses(MetaTransactionalProviderCallerFixture.class);

        EvaluationResult result = NO_TRANSACTIONAL_AROUND_PROVIDER.evaluate(fixtures);

        assertThat(result.hasViolation()).isTrue();
        assertThat(result.getFailureReport().getDetails())
                .anyMatch(detail -> detail.contains(MetaTransactionalProviderCallerFixture.class.getName()
                        + " está anotada con @Transactional"));
    }

    @Test
    @DisplayName("la regla de @Transactional detecta un llamador indirecto del puerto")
    void transactionalRuleDetectsIndirectCaller() {
        JavaClasses fixtures = new ClassFileImporter()
                .importClasses(TransactionalIndirectCallerFixture.class, ProviderCallerFixture.class);

        EvaluationResult result = NO_TRANSACTIONAL_AROUND_PROVIDER.evaluate(fixtures);

        assertThat(result.hasViolation()).isTrue();
        assertThat(result.getFailureReport().getDetails())
                .anyMatch(detail -> detail.contains(
                        TransactionalIndirectCallerFixture.class.getName() + ".execute"))
                .noneMatch(detail -> detail.contains(ProviderCallerFixture.class.getName() + "."));
    }

    @Test
    @DisplayName("la regla de @Transactional alcanza al servicio de ejecución en el código de producción")
    void transactionalRuleCoversExecutionService() {
        JavaClass service = PRODUCTION_CLASSES.get("mx.gilortiz.transactions.transaction.TransactionService");

        assertThat(DEPEND_ON_PROVIDER_PORT.test(service)).isTrue();
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

    private static ArchCondition<JavaClass> notUseTransactional() {
        return new ArchCondition<>("no usar @Transactional en la clase ni en sus métodos") {
            @Override
            public void check(JavaClass javaClass, ConditionEvents events) {
                if (isTransactional(javaClass)) {
                    events.add(SimpleConditionEvent.violated(javaClass,
                            javaClass.getName() + " está anotada con @Transactional"));
                }
                for (JavaMethod method : javaClass.getMethods()) {
                    if (isTransactional(method)) {
                        events.add(SimpleConditionEvent.violated(method,
                                javaClass.getName() + "." + method.getName() + " está anotado con @Transactional"));
                    }
                }
            }
        };
    }

    // isMetaAnnotatedWith cubre anotaciones propias que llevan @Transactional; isAnnotatedWith, la directa.
    private static boolean isTransactional(HasAnnotations<?> element) {
        return TRANSACTIONAL_ANNOTATIONS.stream()
                .anyMatch(name -> element.isAnnotatedWith(name) || element.isMetaAnnotatedWith(name));
    }
}
