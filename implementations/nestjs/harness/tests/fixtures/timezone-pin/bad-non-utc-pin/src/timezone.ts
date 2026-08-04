// Pins the process timezone to UTC. Imported for its side effect only, and it must be the
// very first import of main.ts — see docs/conventions.md, "Timezone rule — store UTC".
process.env.TZ = 'Asia/Seoul'

export {}
