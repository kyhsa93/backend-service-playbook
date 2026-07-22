@RestController
@RequestMapping("/accounts")
public class AccountController {

    @PostMapping
    @Operation(summary = "Open a new account")
    @ApiResponse(responseCode = "201", description = "The account was created.")
    @ApiResponse(responseCode = "400", description = "Request validation failed (VALIDATION_FAILED).")
    public CreateAccountResult createAccount() {
    }
}
