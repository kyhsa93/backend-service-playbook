@RestController
@RequestMapping("/accounts")
@ApiResponse(responseCode = "401", description = "The bearer token is missing, malformed, or invalid.")
public class AccountController {

    @GetMapping
    @Operation(summary = "List accounts", description = "Lists every account for the authenticated requester.")
    @ApiResponse(responseCode = "200", description = "The accounts were found.")
    public GetAccountsResult getAccounts() {
    }
}
