package employees;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class EmployeeTest {

    @Test
    void testEmployee() {
        List<Employee> employees = new ArrayList<>();
        employees.add(new Employee(1L, "John"));
        employees.add(new Employee(2L, "Jane"));

        assertThat(employees).hasSize(2)
                .extracting(Employee::name)
                .containsExactlyInAnyOrder("Jane", "John")
        ;

        assertThat("Jane Doe").startsWith("Jane");
    }
}
