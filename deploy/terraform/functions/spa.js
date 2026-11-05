// CloudFront Function（viewer-request）。動いている間に使う（cloudfront.tf）。
// 最後の部分に拡張子がないパス（/、/books/56 など）は画面の URL なので、index.html を返して React Router に任せる。
// /api/*・/actuator/* は ALB へ渡すビヘイビアなので、ここには来ない
function handler(event) {
  var request = event.request;
  var last = request.uri.split('/').pop();
  if (last.indexOf('.') === -1) {
    request.uri = '/index.html';
  }
  return request;
}
