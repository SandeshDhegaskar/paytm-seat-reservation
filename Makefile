.PHONY: run build test up down burst
run:
	mvn spring-boot:run
build:
	mvn clean package
up:
	docker compose up -d --build
down:
	docker compose down
burst:
	python3 burst/burst.py $${BASE_URL} --show-id $${SHOW_ID} --requests $${REQUESTS:-500} --seat $${SEAT:-A12}
test:
	mvn test
