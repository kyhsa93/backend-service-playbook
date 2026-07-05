package http

import (
	"encoding/json"
	"errors"
	"net/http"

	"github.com/example/order-service/internal/application/command"
	"github.com/example/order-service/internal/application/query"
	"github.com/example/order-service/internal/domain/order"
)

type OrderHandler struct {
	createOrder *command.CreateOrderHandler
	cancelOrder *command.CancelOrderHandler
	getOrder    *query.GetOrderHandler
	getOrders   *query.GetOrdersHandler
}

func NewOrderHandler(
	createOrder *command.CreateOrderHandler,
	cancelOrder *command.CancelOrderHandler,
	getOrder *query.GetOrderHandler,
	getOrders *query.GetOrdersHandler,
) *OrderHandler {
	return &OrderHandler{
		createOrder: createOrder,
		cancelOrder: cancelOrder,
		getOrder:    getOrder,
		getOrders:   getOrders,
	}
}

func (h *OrderHandler) CreateOrder(w http.ResponseWriter, r *http.Request) {
	var body CreateOrderRequest
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
		http.Error(w, "invalid request body", http.StatusBadRequest)
		return
	}
	items := make([]order.ItemInput, len(body.Items))
	for i, it := range body.Items {
		items[i] = order.ItemInput{ItemID: it.ItemID, Name: it.Name, Price: it.Price, Quantity: it.Quantity}
	}
	if err := h.createOrder.Handle(r.Context(), command.CreateOrderCommand{UserID: body.UserID, Items: items}); err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	w.WriteHeader(http.StatusCreated)
}

func (h *OrderHandler) GetOrder(w http.ResponseWriter, r *http.Request) {
	orderID := r.PathValue("id")
	result, err := h.getOrder.Handle(r.Context(), query.GetOrderQuery{OrderID: orderID})
	if err != nil {
		if errors.Is(err, order.ErrNotFound) {
			http.Error(w, err.Error(), http.StatusNotFound)
			return
		}
		http.Error(w, "internal server error", http.StatusInternalServerError)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(result)
}

func (h *OrderHandler) CancelOrder(w http.ResponseWriter, r *http.Request) {
	orderID := r.PathValue("id")
	var body CancelOrderRequest
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
		http.Error(w, "invalid request body", http.StatusBadRequest)
		return
	}
	err := h.cancelOrder.Handle(r.Context(), command.CancelOrderCommand{OrderID: orderID, Reason: body.Reason})
	if err != nil {
		switch {
		case errors.Is(err, order.ErrNotFound):
			http.Error(w, err.Error(), http.StatusNotFound)
		case errors.Is(err, order.ErrAlreadyCancelled), errors.Is(err, order.ErrPaidNotCancellable):
			http.Error(w, err.Error(), http.StatusBadRequest)
		default:
			http.Error(w, "internal server error", http.StatusInternalServerError)
		}
		return
	}
	w.WriteHeader(http.StatusNoContent)
}
