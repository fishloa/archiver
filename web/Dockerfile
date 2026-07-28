FROM nginx:alpine

COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY signin.html /usr/share/nginx/html/signin.html
COPY well-known/ /usr/share/nginx/html/.well-known/

EXPOSE 8080
