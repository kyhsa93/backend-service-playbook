package http

type ItemInput struct {
	ItemID   int    `json:"itemId"`
	Name     string `json:"name"`
	Price    int    `json:"price"`
	Quantity int    `json:"quantity"`
}

type CreateOrderRequest struct {
	UserID string      `json:"userId"`
	Items  []ItemInput `json:"items"`
}

type CancelOrderRequest struct {
	Reason string `json:"reason"`
}
