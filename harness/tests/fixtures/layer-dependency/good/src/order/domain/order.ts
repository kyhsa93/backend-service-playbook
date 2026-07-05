export class Order {
  constructor(public readonly orderId: string) {}

  cancel(): void {
    // pure domain logic, no imports from other layers
  }
}
