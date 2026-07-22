@RestController
@RequestMapping("/accounts")
public class AccountController {

    @PostMapping
    @Operation(summary = "Open a new account", description = "Opens a new account for the authenticated requester.")
    @ApiResponse(responseCode = "201", description = "The account was created.")
    public CreateAccountResult createAccount() {
    }
}
