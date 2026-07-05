package persistence

import (
	"context"
	"database/sql"
	"fmt"
	"strings"
	"time"

	"github.com/example/order-service/internal/domain/order"
)

type OrderRepository struct {
	db *sql.DB
}

// 컴파일 타임 interface 충족 검증
var _ order.Repository = (*OrderRepository)(nil)

func NewOrderRepository(db *sql.DB) *OrderRepository {
	return &OrderRepository{db: db}
}

func (r *OrderRepository) FindByID(ctx context.Context, orderID string) (*order.Order, error) {
	row := r.db.QueryRowContext(ctx,
		`SELECT order_id, user_id, status FROM orders WHERE order_id = $1 AND deleted_at IS NULL`,
		orderID,
	)
	var id, userID, status string
	if err := row.Scan(&id, &userID, &status); err != nil {
		if err == sql.ErrNoRows {
			return nil, order.ErrNotFound
		}
		return nil, fmt.Errorf("find order by id: %w", err)
	}
	items, err := r.findItems(ctx, orderID)
	if err != nil {
		return nil, err
	}
	return order.Reconstitute(id, userID, items, order.Status(status)), nil
}

func (r *OrderRepository) FindAll(ctx context.Context, q order.FindQuery) ([]*order.Order, int, error) {
	args := []any{}
	where := []string{"o.deleted_at IS NULL"}
	i := 1

	if q.UserID != "" {
		where = append(where, fmt.Sprintf("o.user_id = $%d", i))
		args = append(args, q.UserID)
		i++
	}
	if len(q.Status) > 0 {
		placeholders := make([]string, len(q.Status))
		for j, s := range q.Status {
			placeholders[j] = fmt.Sprintf("$%d", i)
			args = append(args, s)
			i++
		}
		where = append(where, fmt.Sprintf("o.status IN (%s)", strings.Join(placeholders, ",")))
	}

	whereClause := strings.Join(where, " AND ")

	var total int
	if err := r.db.QueryRowContext(ctx,
		fmt.Sprintf(`SELECT COUNT(*) FROM orders o WHERE %s`, whereClause), args...,
	).Scan(&total); err != nil {
		return nil, 0, fmt.Errorf("count orders: %w", err)
	}

	args = append(args, q.Take, q.Page*q.Take)
	rows, err := r.db.QueryContext(ctx,
		fmt.Sprintf(`SELECT o.order_id, o.user_id, o.status FROM orders o WHERE %s ORDER BY o.order_id DESC LIMIT $%d OFFSET $%d`, whereClause, i, i+1),
		args...,
	)
	if err != nil {
		return nil, 0, fmt.Errorf("find orders: %w", err)
	}
	defer rows.Close()

	var orders []*order.Order
	for rows.Next() {
		var id, userID, status string
		if err := rows.Scan(&id, &userID, &status); err != nil {
			return nil, 0, err
		}
		items, err := r.findItems(ctx, id)
		if err != nil {
			return nil, 0, err
		}
		orders = append(orders, order.Reconstitute(id, userID, items, order.Status(status)))
	}
	return orders, total, rows.Err()
}

func (r *OrderRepository) Save(ctx context.Context, o *order.Order) error {
	_, err := r.db.ExecContext(ctx,
		`INSERT INTO orders (order_id, user_id, status, updated_at)
		 VALUES ($1, $2, $3, NOW())
		 ON CONFLICT (order_id) DO UPDATE SET status = EXCLUDED.status, updated_at = NOW()`,
		o.OrderID, o.UserID, string(o.Status),
	)
	if err != nil {
		return fmt.Errorf("save order: %w", err)
	}
	return nil
}

func (r *OrderRepository) Delete(ctx context.Context, orderID string) error {
	now := time.Now()
	_, err := r.db.ExecContext(ctx,
		`UPDATE orders SET deleted_at = $1 WHERE order_id = $2 AND deleted_at IS NULL`,
		now, orderID,
	)
	if err != nil {
		return fmt.Errorf("delete order: %w", err)
	}
	return nil
}

func (r *OrderRepository) findItems(ctx context.Context, orderID string) ([]order.Item, error) {
	rows, err := r.db.QueryContext(ctx,
		`SELECT item_id, name, price, quantity FROM order_items WHERE order_id = $1 AND deleted_at IS NULL`,
		orderID,
	)
	if err != nil {
		return nil, fmt.Errorf("find order items: %w", err)
	}
	defer rows.Close()

	var items []order.Item
	for rows.Next() {
		var item order.Item
		if err := rows.Scan(&item.ItemID, &item.Name, &item.Price, &item.Quantity); err != nil {
			return nil, err
		}
		items = append(items, item)
	}
	return items, rows.Err()
}
