import { OutboxWriter } from '@/outbox/outbox-writer'
import { Shipment } from '../domain/shipment'

export class ShipmentRepositoryImpl {
  constructor(private readonly outboxWriter: OutboxWriter) {}

  public async saveShipment(shipment: Shipment): Promise<void> {
    await this.outboxWriter.saveAll(shipment.domainEvents)
    shipment.clearEvents()
  }
}
