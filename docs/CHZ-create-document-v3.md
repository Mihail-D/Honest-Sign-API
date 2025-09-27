# Честный знак: Создание документа (v3)

Эндпоинт: POST /api/v3/lk/documents/create

Запрос может принимать параметр product_group в query (pg), пример: /api/v3/lk/documents/create?pg=milk. Допустимо также передавать product_group в теле запроса (см. ниже).

Заголовки
- Content-Type: application/json
- Authorization: Bearer <токен>

Пример запроса (cURL) — с product_group в query
```
curl "https://ismp.crpt.ru/api/v3/lk/documents/create?pg=milk" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <ТОКЕН>" \
  --data-binary '{
    "product_document": "<документ в base64>",
    "document_format": "MANUAL",
    "type": "LP_INTRODUCE_GOODS",
    "signature": "<откреплённая подпись (УКЭП, CMS/PKCS#7) в base64>"
  }'
```

Альтернативный пример — с product_group в теле
```
curl "https://ismp.crpt.ru/api/v3/lk/documents/create" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <ТОКЕН>" \
  --data-binary '{
    "product_document": "<документ в base64>",
    "document_format": "MANUAL",
    "type": "LP_INTRODUCE_GOODS",
    "signature": "<откреплённая подпись (УКЭП, CMS/PKCS#7) в base64>",
    "product_group": "milk"
  }'
```

Шаблон тела запроса (JSON)
```
{
  "document_format": "MANUAL|XML|CSV",
  "product_document": "<Base64 исходного документа>",
  "product_group": "<код группы, опционально>",
  "signature": "<Base64 откреплённой подписи (УКЭП, CMS/PKCS#7)>",
  "type": "<тип документа>"
}
```

Тело запроса (описание полей)
- document_format: enum (обязателен)
  - Формат исходного документа:
    - MANUAL — JSON (в product_document кладётся Base64 от JSON-строки, например Base64(JSON.stringify(...)))
    - XML — XML (в product_document кладётся Base64 от XML)
    - CSV — CSV (в product_document кладётся Base64 от CSV)
- product_document: string (обязателен)
  - Содержимое исходного документа, закодированное в Base64 (см. привязку к document_format выше).
- type: enum (обязателен)
  - Тип документа (см. перечень ниже), например: LP_INTRODUCE_GOODS.
- signature: string (обязателен)
  - Откреплённая подпись (УКЭП, как правило CMS/PKCS#7) в Base64.
- product_group: string (опционален)
  - Код группы товаров; может указываться как поле тела или как query-параметр `pg`.

Параметры запроса (query)
- pg (product_group): string (опционален)
  - Группа товаров. Допустимо вместо `pg` передавать поле `product_group` в теле.
  - Примеры кодов по документации ЧЗ (не исчерпывающе):
    - clothes — одежда и изделия лёгкой промышленности
    - shoes — обувь
    - tobacco — табачная продукция
    - perfumery — парфюмерия
    - tires — шины и покрышки
    - electronics — электроника (включая фото/видеотехнику и крупную бытовую)
    - pharma — лекарственные средства
    - milk — молочная продукция
    - bicycle — велосипеды и рамы
    - wheelchairs — кресла-коляски

Пример успешного ответа
```
{
  "value": "9abd3d41-76bc-4542-a88e-b1f7be8130b5"
}
```

Структура ответа (сводка)
- value: string — идентификатор созданного документа (UUID)
- При ошибке возможны поля:
  - code: string — код ошибки
  - error_message: string — сообщение об ошибке
  - description: string — описание ошибки

Перечень значений поля type (enum)
- AGGREGATION_DOCUMENT — агрегация (JSON)
- AGGREGATION_DOCUMENT_CSV — агрегация (CSV)
- AGGREGATION_DOCUMENT_XML — агрегация (XML)
- DISAGGREGATION_DOCUMENT — деагрегация (JSON)
- DISAGGREGATION_DOCUMENT_CSV — деагрегация (CSV)
- DISAGGREGATION_DOCUMENT_XML — деагрегация (XML)
- REAGGREGATION_DOCUMENT — реагрегация (JSON)
- REAGGREGATION_DOCUMENT_CSV — реагрегация (CSV)
- REAGGREGATION_DOCUMENT_XML — реагрегация (XML)
- LP_INTRODUCE_GOODS — ввод в оборот (JSON)
- LP_INTRODUCE_GOODS_CSV — ввод в оборот (CSV)
- LP_INTRODUCE_GOODS_XML — ввод в оборот (XML)
- LP_SHIP_GOODS — отгрузка (JSON)
- LP_SHIP_GOODS_CSV — отгрузка (CSV)
- LP_SHIP_GOODS_XML — отгрузка (XML)
- LP_ACCEPT_GOODS — приёмка (JSON)
- LP_ACCEPT_GOODS_XML — приёмка (XML)
- LK_REMARK — перемаркировка (JSON)
- LK_REMARK_CSV — перемаркировка (CSV)
- LK_REMARK_XML — перемаркировка (XML)
- LK_RECEIPT — чек по приёмке из ИСМП (JSON)
- LK_RECEIPT_XML — чек по приёмке из ИСМП (XML)
- LK_RECEIPT_CSV — чек по приёмке из ИСМП (CSV)
- LP_GOODS_IMPORT — импорт (JSON)
- LP_GOODS_IMPORT_CSV — импорт (CSV)
- LP_GOODS_IMPORT_XML — импорт (XML)
- LP_CANCEL_SHIPMENT — отмена отгрузки (JSON)
- LP_CANCEL_SHIPMENT_CSV — отмена отгрузки (CSV)
- LP_CANCEL_SHIPMENT_XML — отмена отгрузки (XML)
- LK_KM_CANCELLATION — аннулирование КМ (JSON)
- LK_KM_CANCELLATION_CSV — аннулирование КМ (CSV)
- LK_KM_CANCELLATION_XML — аннулирование КМ (XML)
- LK_APPLIED_KM_CANCELLATION — аннулирование КМ (применённые) (JSON)
- LK_APPLIED_KM_CANCELLATION_CSV — аннулирование КМ (применённые) (CSV)
- LK_APPLIED_KM_CANCELLATION_XML — аннулирование КМ (применённые) (XML)
- LK_CONTRACT_COMMISSIONING — ввод в оборот по договору (JSON)
- LK_CONTRACT_COMMISSIONING_CSV — ввод в оборот по договору (CSV)
- LK_CONTRACT_COMMISSIONING_XML — ввод в оборот по договору (XML)
- LK_INDI_COMMISSIONING — ввод в оборот единичного товара (JSON)
- LK_INDI_COMMISSIONING_CSV — ввод в оборот единичного товара (CSV)
- LK_INDI_COMMISSIONING_XML — ввод в оборот единичного товара (XML)
- LP_SHIP_RECEIPT — приёмка отгрузки (JSON)
- LP_SHIP_RECEIPT_CSV — приёмка отгрузки (CSV)
- LP_SHIP_RECEIPT_XML — приёмка отгрузки (XML)
- OST_DESCRIPTION — описание остатков (JSON)
- OST_DESCRIPTION_CSV — описание остатков (CSV)
- OST_DESCRIPTION_XML — описание остатков (XML)
- CROSSBORDER — кроссбордер (JSON)
- CROSSBORDER_CSV — кроссбордер (CSV)
- CROSSBORDER_XML — кроссбордер (XML)
- LP_INTRODUCE_OST — ввод остатков (JSON)
- LP_INTRODUCE_OST_CSV — ввод остатков (CSV)
- LP_INTRODUCE_OST_XML — ввод остатков (XML)
- LP_RETURN — возврат (JSON)
- LP_RETURN_CSV — возврат (CSV)
- LP_RETURN_XML — возврат (XML)
- LP_SHIP_GOODS_CROSSBORDER — отгрузка для кроссбордера (JSON)
- LP_SHIP_GOODS_CROSSBORDER_CSV — отгрузка для кроссбордера (CSV)
- LP_SHIP_GOODS_CROSSBORDER_XML — отгрузка для кроссбордера (XML)
- LP_CANCEL_SHIPMENT_CROSSBORDER — отмена отгрузки для кроссбордера (JSON)

Связанные эндпоинты (по исходнику)
- /api/v3/lk/documents/commissioning/indi/create — ввод в оборот единичного товара
- /api/v3/lk/documents/commissioning/contract/create — ввод в оборот по договору
- /api/v3/lk/documents/send — отправка документов
- /api/v3/lk/import/send — импорт (пакетная отправка)
- /api/v3/lk/receipt/send — отправка чеков
- /api/v3/lk/documents/shipment/create — создание отгрузки
- /api/v3/lk/documents/shipment/cancel — отмена отгрузки
- /api/v3/lk/documents/reaggregation/create — реагрегация
- /api/v3/lk/remarking/send — перемаркировка
- /api/v3/lk/documents/acceptance/create — приёмка
- /api/v3/lk/documents/disaggregation/create — деагрегация
- /api/v3/lk/documents/km/cancellation/applied/create — аннулирование КМ (применённые)
- /api/v3/lk/documents/km/cancellation/create — аннулирование КМ
- /api/v3/lk/documents/aggregation/create — агрегация

Примечания
- Метод универсальный: может применяться вместо специализированных методов создания документов по конкретным типам (при наличии соответствующей конфигурации).
- В исходном RTF обнаружены артефакты кодировки; приведённый конспект нормализован.
