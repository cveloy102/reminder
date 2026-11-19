/// <reference path="../pb_data/types.d.ts" />

// Open registration only when SVALKA_ALLOW_SIGNUP=true. The server owner (superuser) can always create accounts.
onRecordCreateRequest((e) => {
  if ($os.getenv("SVALKA_ALLOW_SIGNUP") !== "true" && !e.hasSuperuserAuth()) {
    throw new ForbiddenError("Регистрация на этом сервере закрыта. Попроси владельца создать аккаунт.");
  }
  e.next();
}, "users");

// Lets the apps check that the address really is a СВАЛКА server and whether sign-up is open.
routerAdd("GET", "/api/svalka/info", (e) => {
  return e.json(200, {
    app: "svalka-sync",
    version: 1,
    signup: $os.getenv("SVALKA_ALLOW_SIGNUP") === "true",
  });
});

// Never let an older edit overwrite a newer one (two devices editing the same record at once).
onRecordUpdateRequest((e) => {
  const before = e.record.original().getInt("updatedAt");
  const after = e.record.getInt("updatedAt");
  if (after < before) {
    throw new ApiError(409, "На сервере более новая версия записи.");
  }
  e.next();
}, "items");
