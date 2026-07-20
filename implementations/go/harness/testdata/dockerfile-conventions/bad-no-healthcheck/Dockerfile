FROM golang:1.25-alpine AS build
WORKDIR /app
RUN go build -o /bin/server ./cmd/server

FROM gcr.io/distroless/static-debian12
COPY --from=build /bin/server /bin/server
ENTRYPOINT ["/bin/server"]
