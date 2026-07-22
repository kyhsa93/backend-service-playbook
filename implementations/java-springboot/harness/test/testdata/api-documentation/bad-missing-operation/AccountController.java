@RestController
@RequestMapping("/accounts")
public class AccountController {

    @PostMapping
    @ApiResponse(responseCode = "201", description = "The account was created.")
    @ApiResponse(responseCode = "400", description = "Request validation failed (VALIDATION_FAILED).")
    public CreateAccountResult createAccount() {
    }
}
