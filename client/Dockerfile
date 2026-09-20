FROM node:22-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
# Relative API base: the browser calls the frontend origin and nginx proxies /api/ to the backend.
ARG VITE_RAZORPAY_KEY_ID=
ENV VITE_API_BASE_URL=/api/v1.0 VITE_RAZORPAY_KEY_ID=$VITE_RAZORPAY_KEY_ID
RUN npm run build

FROM nginx:1.27-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
