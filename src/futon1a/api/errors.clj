(ns futon1a.api.errors
  "Map layer error maps to HTTP response shapes.")

(defn error->response
  "Convert an {:error {...}} map into a HTTP response map.

   Expected error shape:
   {:error/layer n
    :error/status code
    :error/reason kw
    :error/context map}
  "
  [{:keys [error]}]
  (if (nil? error)
    {:status 500
     :body {:error {:reason :unknown}}}
    (let [{:error/keys [status reason context layer]} error]
      {:status (or status 500)
       :body {:error {:layer layer
                      :reason reason
                      :context context}}})))
