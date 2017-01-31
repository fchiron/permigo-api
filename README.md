# permigo-api

The goal of this project is to provide a basic read-only API for Permigo.
It can not be considered as stable, and is likely to break in case of changes on Permigo's website.
Work in progress.

## Context

I needed to know when slots were available to book a driving lesson.
The only way to do that was to go on the website and manually check the timetable of each instructor, for every week.
I wanted to automate the process and be notified when slots are available, and this API is a component required to achieve that goal.

## API

### `POST /login`

Login on the Permigo website to get a `_permigo_session` cookie, generate an access_token, bind it to the cookie, and return it.

Request payload:
```json
{
  "email": "your.email@address.com",
  "password": "p4ssw0rd"
}
```

Response payload:
```json
{
  "access_token": "e9394272-e4dd-4aa0-8dad-fd8e0307f3e8"
}
```

### `GET /session`

Get the data associated to the given access token.

Query parameters:
- `access_token`: access token obtained via the `/login` route

Response payload:
```json
{
  "email": "your.email@address.com",
  "session": "<the _permigo_session cookie value obtained when logging in>"
}
```

### `GET /cities`

Get cities available for the Permigo account.

Query parameters:
- `access_token`: access token obtained via the `/login` route

Response payload:
```json
[
  {
    "id": 17200,
    "name": "Nantes (44000)"
  },
  {
    "id": 17202,
    "name": "Nantes (44200)"
  }
]
```

### `GET /instructors`

Get the instructors available for a given city.

Query parameters:
- `access_token`: access token obtained via the `/login` route
- `city_id`: city id obtained via the `/cities` route

Response payload:
```json
[
  {
    "id": 7161,
    "name": "Jerome"
  },
  {
    "id": 13517,
    "name": "Marie-Bénédicte"
  }
]
```

### `GET /available_slots`

Get the available slots for a given instructor and date range.

Query parameters:
- `access_token`: access token obtained via the `/login` route
- `instructor_id`: instructor id obtained via the `/instructors` route
- `start`: start date formatted as `YYYY-MM-DD`
- `end`: end date (exclusive) formatted as `YYYY-MM-DD`
- `ignore_max_workhours` (optional): boolean, if `true`, return slots even if the instructor already has 35 hours booked for the week. Defaults to `false`.

Response payload:
```json
[
  {
    "start": "2017-05-02T12:00Z",
    "end": "2017-05-02T13:00Z"
  },
  {
    "start": "2017-05-02T14:00Z",
    "end": "2017-05-02T15:00Z"
  }
]
```
