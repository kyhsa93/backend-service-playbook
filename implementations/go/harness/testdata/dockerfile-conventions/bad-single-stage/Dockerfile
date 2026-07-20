FROM golang:1.25-alpine
WORKDIR /app
RUN go build -o /bin/server ./cmd/server
HEALTHCHECK --interval=10s CMD ["/bin/server", "-healthcheck"]
ENTRYPOINT ["/bin/server"]
