package order

type Item struct {
	ItemID   int
	Name     string
	Price    int
	Quantity int
}

func newItem(itemID int, name string, price, quantity int) (Item, error) {
	if price <= 0 {
		return Item{}, ErrInvalidPrice
	}
	if quantity <= 0 {
		return Item{}, ErrInvalidQuantity
	}
	return Item{ItemID: itemID, Name: name, Price: price, Quantity: quantity}, nil
}
