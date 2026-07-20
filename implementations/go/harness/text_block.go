package main

// extractBalancedBlock extracts the text between a matching pair of open/close
// bytes (e.g. '(' / ')' or '{' / '}'), starting the scan at src[openIdx] (which
// must itself be the opening byte). String and rune literals are skipped whole
// so that a stray '(' / ')' / '{' / '}' inside a string constant (a format
// string like "(%w)" for example) never desyncs the depth counter — several
// rules (soft-delete-filter's Find* method bodies, typed-errors-only's
// fmt.Errorf/errors.New call arguments) need this same "balanced Go source
// span" extraction and would otherwise have to duplicate it.
//
// Returns the substring strictly between the matching pair (exclusive of both
// delimiters). If no matching close byte is found, returns the remainder of
// src from openIdx+1.
func extractBalancedBlock(src string, openIdx int, open, close byte) string {
	depth := 0
	var quote byte
	i := openIdx
	for i < len(src) {
		c := src[i]
		if quote != 0 {
			if c == '\\' && quote == '"' {
				i += 2
				continue
			}
			if c == quote {
				quote = 0
			}
			i++
			continue
		}
		switch c {
		case '"', '`':
			quote = c
		case open:
			depth++
		case close:
			depth--
			if depth == 0 {
				return src[openIdx+1 : i]
			}
		}
		i++
	}
	return src[openIdx+1:]
}
