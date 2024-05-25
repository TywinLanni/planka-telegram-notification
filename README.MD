# Telegram planka notification bot

This is a telegram bot that uses the planka api to send card change to telegram.

## Requirements

1. [Mongo DB](https://www.mongodb.com/) 

## How to use

1. Create a telegram bot using the [BotFather](https://t.me/botfather)
2. Create a planka service account and add him to all boards as viewer 
3. Clone this repo
4. Create docker image
```bash
docker build -t tywinlanni/planka-notification-bot:0.0.7 --build-arg jar_version=0.0.7 .
```

```bash
docker image push tywinlanni/planka-notification-bot:latest
```

## Run

```bash
docker create --name bot --network="host" -e PLANKA_CONNECTION_STRING=http://0.0.0.0:3000 -e PLANKA_SERVICE_USERNAME=xxx@gmail.com -e PLANKA_SERVICE_PASSWORD=12345678 -e TELEGRAM_BOT_TOKEN=xxxxxx planka-notification-bot
```

```bash
docker start bot
```

## Environment variables

- `PLANKA_CONNECTION_STRING` - url to planka
- `PLANKA_SERVICE_USERNAME` - service account login
- `PLANKA_SERVICE_PASSWORD` - service account password
- `MONGO_CONNECTION_STRING` - mongo db connection string (default `mongodb://localhost`)
- `DATABASE_NAME` - mongo database name (default `Planka`)
- `TELEGRAM_BOT_TOKEN` - telegram bot token
- `DISABLED_PLANKA_LIST_NAMES` - specific list to disable notification comma separated (improve bot speed) not require