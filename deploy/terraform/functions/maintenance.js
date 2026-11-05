// CloudFront Function（viewer-request）。止めている間に使う（cloudfront.tf）。
// どのパスでも「止めています」のページ（S3 の maintenance/index.html）を返す
function handler(event) {
  var request = event.request;
  request.uri = '/maintenance/index.html';
  return request;
}
