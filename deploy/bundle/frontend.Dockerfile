FROM nginxinc/nginx-unprivileged:1.28.0-alpine-slim
COPY deploy/nginx.conf /etc/nginx/nginx.conf
COPY --chown=101:101 frontend/dist/ /usr/share/nginx/supportflow/
USER 101:101
EXPOSE 8080
ENTRYPOINT ["nginx"]
CMD ["-g", "daemon off;"]
