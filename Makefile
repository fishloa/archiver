.PHONY: dev-backend dev-scraper dev-frontend test lint

# Backend
dev-backend:
	cd backend && ./gradlew bootRun

test-backend:
	cd backend && ./gradlew test

lint-backend:
	cd backend && ./gradlew spotlessCheck

# Scraper
dev-scraper:
	cd scraper-cz && python -m scraper_cz.main

test-scraper:
	cd scraper-cz && pytest -v

lint-scraper:
	ruff check scraper-cz/ && ruff format --check scraper-cz/

# OCR Worker
test-ocr:
	cd ocr-worker-paddle && pytest -v

lint-ocr:
	ruff check ocr-worker-paddle/ && ruff format --check ocr-worker-paddle/

# PDF Worker
test-pdf:
	cd pdf-worker && pytest -v

lint-pdf:
	ruff check pdf-worker/ && ruff format --check pdf-worker/

# Entity Worker
test-entity:
	cd entity-worker && pytest -v

lint-entity:
	ruff check entity-worker/ && ruff format --check entity-worker/

# Frontend
dev-frontend:
	cd frontend && npm run dev

test-frontend:
	cd frontend && npm test

lint-frontend:
	cd frontend && npx eslint . && npx prettier --check .

# Smoke test (live site)
test-smoke:
	web/test-endpoints.sh

# All
test: test-backend test-scraper test-ocr test-pdf test-entity test-frontend
lint: lint-backend lint-scraper lint-ocr lint-pdf lint-entity lint-frontend
