package http

type GetPaymentsResponse struct {
	Data  []string `json:"data"`
	Count int      `json:"count"`
}
