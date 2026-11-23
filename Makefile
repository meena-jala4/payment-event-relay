.PHONY: run test build docker-build compose-up k8s-validate

run:            ## Start the service (embedded H2, fraud stub on) at http://localhost:8080
	./mvnw spring-boot:run

test:           ## Unit + integration tests, coverage report in target/site/jacoco
	./mvnw verify

build:
	./mvnw -B package

docker-build:
	docker build -t payment-event-relay:local .

compose-up:     ## Run against PostgreSQL
	docker compose up --build

k8s-validate:   ## Render and client-side validate both environments
	kubectl apply --dry-run=client -f k8s/base/
	kubectl apply --dry-run=client -k k8s/overlays/dev
	kubectl apply --dry-run=client -k k8s/overlays/prod
