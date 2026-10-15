FROM nginx:1.27-alpine
COPY website/nginx.conf /etc/nginx/conf.d/default.conf
COPY website/index.html website/styles.css website/site.js website/site-config.js website/favicon.svg /usr/share/nginx/html/
COPY website/site-config.js.template /etc/flowpilot-site-config.js.template
COPY website/docker-entrypoint.sh /docker-entrypoint.d/40-flowpilot-site-config.sh
RUN chmod +x /docker-entrypoint.d/40-flowpilot-site-config.sh
EXPOSE 80
