package employees;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

public record EmployeeResource(Long id, @NotBlank String name) {

}
