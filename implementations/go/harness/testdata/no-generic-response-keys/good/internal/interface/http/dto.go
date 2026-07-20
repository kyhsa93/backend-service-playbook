package http

type GetPaymentsResponse struct {
	Payments []string `json:"payments"`
	Count    int      `json:"count"`
}
