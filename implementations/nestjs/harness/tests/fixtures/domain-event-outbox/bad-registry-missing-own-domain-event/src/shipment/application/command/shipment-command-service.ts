import { Shipment } from '../../domain/shipment'

export class ShipmentCommandService {
  public async dispatchShipment(shipmentId: string, carrier: string): Promise<void> {
    const shipment = new Shipment(shipmentId, 'pending')
    shipment.dispatch(carrier)
  }
}
