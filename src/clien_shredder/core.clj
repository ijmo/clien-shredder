(ns clien-shredder.core
  (:require [clojure.string :as string]
            [clojure.core.async :as async]
            [clj-http.client :as client]
            [hickory.core :as hickory]
            [hickory.select :as s]
            [slingshot.slingshot :refer [throw+ try+]]))


(defonce num-threads 3)
(defonce user-id "your id here")
(defonce user-pw "your password here")


(declare ^:dynamic *csrf*)
(declare ^:dynamic *thread-no*)


(defn url [path]
  (str "https://www.clien.net/service" path))


(defmulti http-request
  (fn [method & more] method))


(defmethod http-request :get
  [method path & opts]
  (client/get (url path) (merge {:throw-exceptions false} (apply array-map opts))))


(defmethod http-request :post
  [method path & opts]
  (client/post (url path) (merge {:throw-exceptions false} (apply array-map opts))))


(defn page-tree [path]
  (-> (http-request :get path)
      :body
      hickory/parse
      hickory/as-hickory))


(defn get-csrf []
  (let [htree (page-tree "/")]
    (-> (s/select (s/attr "name" (partial = "_csrf")) htree)
        first :attrs :value)))


(defmacro with-cookie-store [& body]
  `(binding [clj-http.core/*cookie-store* (clj-http.cookies/cookie-store)]
     ~@body))


(defmacro with-csrf [& body]
  `(binding [*csrf* (get-csrf)]
     ~@body))


(defn login []
  (let [resp (http-request :post "/login"
                           :form-params {"userId" user-id
                                         "userPassword" user-pw
                                         "_csrf" (get-csrf)})]
    (println (case (:status resp)
               200 "Logged in"
               "Login failed."))))


(def not-kin? #(not (string/starts-with? % "/service/board/kin")))


(defn get-posts [page-no type acc]
  (println "Searching posts for deleting" type "on page" page-no)
  (let [path (str "/mypage/myArticle?type=" type "&po=" page-no)
        htree (page-tree path)
        rows (s/select (s/and (s/class "list_item")
                              (s/class "myArticle")
                              (s/class "symph_row")) htree)
        undeleted (->> rows
                       (filter #(not (string/includes? (get-in % [:attrs :class]) "blocked")))
                       (map #(-> (s/select (s/and (s/tag :a) (s/class "list_subject")) %) first :attrs :href)))]
    (if (= (count rows) 0)
      acc
      (recur (inc page-no) type (into [] (concat acc undeleted))))))


(defn get-comments [post-url]
  (try+
   (print *thread-no* "Searching..." post-url)
   (flush)
   (let [htree (page-tree post-url)
         comments (->> (s/select (s/and (s/class "comment_row")
                                        (s/class "by-me")) htree)
                       (map #(get-in % [:attrs :data-comment-sn]))
                       (map #(str "/api" post-url "/comment/delete/" %)))]
     (when (seq comments)
       (println "comments:" comments))
     comments)
   (catch [:status 404] {:keys [request-time headers body]}
     (println "not found:" post-url)
     (flush))
   (catch Object _
     (println "not found:" post-url)
     (flush))))


(def targets (atom nil))


(defn pop-target! []
  (dosync (if-let [target (first @targets)]
            (do (swap! targets subvec 1)
              target)
            nil)))


(defn try-delete-comments-in-post
  "Returns true if post is found, false otherwise"
  []
  (if-let [post-url (pop-target!)]
    (when-let [comment-urls (get-comments post-url)]
      (doseq [comment-url comment-urls]
        (let [resp (http-request :post comment-url
                           :headers {"X-CSRF-TOKEN" *csrf*}
                           :throw-exceptions false)]
          (println *thread-no* comment-url (case (:status resp)
                                             200 "Deleted"
                                             404 "Not found"
                                             "Failed"))
          (Thread/sleep 3500)))
      true)
    false))


(defn delete-comments-loop [thread-no]
  (binding [*thread-no* thread-no]
    (with-cookie-store
      (login)
      (with-csrf
        (println *thread-no* "csrf:" *csrf*)
        (while (try-delete-comments-in-post))))))


(defn delete-comments []
  (with-cookie-store
    (login)
    (reset! targets (into [] (set (get-posts 0 "comments" []))))
    (println "targets: " (count @targets))
    (let [chans (doall (map #(async/go (delete-comments-loop %)) (range num-threads)))]
      (doseq [c chans] (async/<!! c))))) ;; block until all go blocks end


(defn try-delete-post
  []
  (if-let [post-url (pop-target!)]
    (let [post-no (re-find #"\d+$" post-url)
          post-delete-url (url (str "/api" (string/replace post-url #"\d+$" "delete")))
          resp (http-request :post post-delete-url
                             :form-params {"boardSn" post-no}
                             :headers {"X-CSRF-TOKEN" *csrf*}
                             :throw-exceptions false)]
      (println *thread-no* post-url (case (:status resp)
                                      200 "Deleted"
                                      404 "Not found"
                                      "Failed"))
      (Thread/sleep 3500)
      true)
    false))


(defn delete-posts-loop [thread-no]
  (binding [*thread-no* thread-no]
    (with-cookie-store
      (login)
      (with-csrf
        (println *thread-no* "csrf:" *csrf*)
        (while (try-delete-post))))))


(defn delete-posts []
  (with-cookie-store
    (login)
    (reset! targets (into [] (set (get-posts 0 "articles" []))))
    (println "targets: " (count @targets))
    (let [chans (doall (map #(async/go (delete-posts-loop %)) (range num-threads)))]
      (doseq [c chans] (async/<!! c)))))


; (delete-comments)