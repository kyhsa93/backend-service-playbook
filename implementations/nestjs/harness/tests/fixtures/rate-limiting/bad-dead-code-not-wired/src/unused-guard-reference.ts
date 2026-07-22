// A dead-code scenario that used to slip past a naive (string-matching) check that passed as
// long as the words APP_GUARD and ThrottlerGuard existed somewhere in the file — in reality
// it's an unused import/comment that's never wired into any @Module's providers or any controller's @UseGuards().
// APP_GUARD, ThrottlerGuard
export const NOTE = 'ThrottlerGuard and APP_GUARD exist only as text here and are not actually wired up'
