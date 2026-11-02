import { useLocation } from 'react-router'

// 今いる URL を画面に出す（部品のテストで、移動したかを確かめるため）
export default function CurrentLocation() {
  const location = useLocation()
  return <output aria-label="今の URL">{location.pathname + location.search}</output>
}
