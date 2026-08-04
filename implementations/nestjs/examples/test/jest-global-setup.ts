// Jest globalSetup — pins the whole test run to UTC, so a test observes exactly the timezone
// the running service does and a timestamp written through the app is the UTC instant
// regardless of the developer's machine.
//
// Importing src/config/timezone.config.ts (the same module src/main.ts loads first, so there is one source of
// truth for the pin) sets process.env.TZ here, in the REAL Node process, before Jest builds any
// test environment or forks any worker:
//   - workers are forked afterwards and inherit TZ=UTC in their environment, so they are born
//     in UTC instead of having to switch zone mid-flight;
//   - a --runInBand run (jest.e2e.config.ts) forks nothing and simply keeps this process's zone.
//
// It has to be globalSetup rather than setupFiles: setupFiles runs INSIDE the test environment,
// where `process.env` is a sandboxed copy of the real one. Assigning TZ there updates that
// object but never reaches the runtime, so `new Date().getTimezoneOffset()` keeps returning the
// host's offset and the pin silently does nothing. globalSetup runs outside the sandbox.
//
// It lives in the Jest config rather than as a `TZ=UTC` prefix on the npm script because the
// config is honoured however the run was started — `npx jest path/to/one.spec.ts`, or an IDE's
// per-test run button, neither of which goes through package.json.
import '../src/config/timezone.config'

export default function globalSetup(): void {
  // The pin is the side effect of the import above; there is nothing further to do here.
}
