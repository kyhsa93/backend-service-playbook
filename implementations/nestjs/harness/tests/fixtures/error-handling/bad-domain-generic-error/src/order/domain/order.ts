export class Order {
  public cancel(): void {
    // a raw string — not a <Domain>ErrorMessage enum reference (an untyped error)
    throw new Error('The order is already cancelled.')
  }
}
