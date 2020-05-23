(ns charon.models
  "DTOs and schemas."
  (:require [schema.core :as s]
            [schema-tools.core :as st]))

(defprotocol Resource
  (file-name [_]))

(defrecord Attachment [page-id title]
  Resource
  (file-name [a]
    (format "%s-%s" (.page-id a) title)))

(defrecord ContentWithAttachments [content attachments])

(s/defschema Config
  {:confluence-url              s/Str
   :output                      s/Str
   :space                       s/Str
   (s/optional-key :debug)      s/Bool
   (s/optional-key :page)       s/Str
   (s/optional-key :page-url)   s/Str
   (s/optional-key :space-url)  s/Str
   (s/optional-key :-overwrite) s/Bool})

(s/defschema Page {s/Keyword s/Any})

(s/defschema Context
  (st/merge
    Config
    {(s/optional-key :space-pages) [Page]
     (s/optional-key :attachment->url) {Attachment s/Str}
     (s/optional-key :space-attachments) #{Attachment}
     (s/optional-key :pages) [Page]
     (s/optional-key :attachments) #{Attachment}}))
