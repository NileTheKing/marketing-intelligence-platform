#!/usr/bin/env python3
"""Demo data seeder: three months of a mid-size commerce mall, written straight into MySQL,
Elasticsearch and Redis of the local compose stack.

Everything is generated relative to the moment the script runs, because the dashboards query
windows such as "the last 30 days". The same --seed always produces the same data.

    python3 scripts/demo-seed/seed.py --users 5000          # small trial
    python3 scripts/demo-seed/seed.py --users 200000        # full demo (~6M behavior events)

The script truncates the demo-owned MySQL tables, deletes the behavior-event index and the FCFS
Redis keys before loading. Stdlib only.
"""
import argparse
import bisect
import json
import math
import random
import subprocess
import sys
import urllib.request
from datetime import datetime, timedelta, timezone

KST = timezone(timedelta(hours=9))
ES_URL = "http://127.0.0.1:9200"
ES_INDEX = "axon.event.behavior"
MYSQL_CONTAINER = "axon-mysql"
REDIS_CONTAINER = "axon-redis"
FULL_USERS = 200_000

# ── Catalog ─────────────────────────────────────────────────────────────────────────────
CATEGORIES = {
    "DIGITAL": (["노바텍", "루멘", "오르빗"],
                [("무선 충전 패드", 29_000), ("보조배터리 20000mAh", 39_000), ("블루투스 스피커", 89_000),
                 ("기계식 키보드", 129_000), ("무선 마우스", 45_000), ("태블릿 거치대", 32_000),
                 ("USB-C 허브", 59_000), ("노이즈캔슬링 헤드폰", 249_000), ("스마트 체중계", 49_000),
                 ("액션캠", 319_000), ("27인치 모니터", 289_000), ("웹캠 FHD", 69_000)]),
    "FASHION": (["모노웨어", "노스폴", "데일리핏"],
                [("경량 패딩", 89_000), ("울 니트", 69_000), ("트렌치 코트", 189_000), ("와이드 데님", 59_000),
                 ("러닝 스니커즈", 119_000), ("캐시미어 머플러", 79_000), ("후드 집업", 65_000),
                 ("코듀로이 셔츠", 49_000), ("레더 벨트", 39_000), ("플리스 자켓", 99_000)]),
    "BEAUTY": (["클리어랩", "소피아", "그린디"],
               [("비타민 세럼", 38_000), ("수분 크림", 32_000), ("톤업 선크림", 24_000), ("쿠션 팩트", 36_000),
                ("립 틴트", 18_000), ("클렌징 오일", 22_000), ("시트 마스크 10매", 15_000), ("앰플 미스트", 21_000)]),
    "LIVING": (["하우스앤", "온더테이블", "무드랩"],
               [("핸드드립 세트", 59_000), ("스톤 머그 2P", 28_000), ("디퓨저 200ml", 34_000),
                ("구스 이불", 189_000), ("수납 바스켓", 19_000), ("무드등", 39_000), ("주방 칼 세트", 79_000),
                ("극세사 러그", 69_000)]),
    "FOOD": (["팜투데이", "한결", "산들"],
             [("견과류 선물세트", 39_000), ("스페셜티 원두 1kg", 42_000), ("홍삼 스틱 30포", 59_000),
              ("제철 사과 5kg", 45_000), ("그릭 요거트 6입", 18_000), ("수제 그래놀라", 16_000),
              ("올리브유 2병", 29_000)]),
}
CATEGORY_WEIGHTS = {"DIGITAL": 0.30, "FASHION": 0.27, "BEAUTY": 0.18, "LIVING": 0.15, "FOOD": 0.10}
VARIANTS = ["", " 프로", " 라이트", " 에디션", " 2세대", " 미니", " 플러스"]

SURNAMES = [("김", "kim", 21), ("이", "lee", 15), ("박", "park", 8), ("최", "choi", 5), ("정", "jung", 5),
            ("강", "kang", 2), ("조", "cho", 2), ("윤", "yoon", 2), ("장", "jang", 2), ("임", "lim", 2),
            ("한", "han", 1), ("오", "oh", 1), ("서", "seo", 1), ("신", "shin", 1), ("권", "kwon", 1),
            ("황", "hwang", 1), ("안", "ahn", 1), ("송", "song", 1), ("홍", "hong", 1)]
GIVEN = [("지윤", "jiyoon"), ("민준", "minjun"), ("서연", "seoyeon"), ("도윤", "doyoon"), ("하은", "haeun"),
         ("예준", "yejun"), ("수아", "sua"), ("시우", "siwoo"), ("지호", "jiho"), ("하준", "hajun"),
         ("서준", "seojun"), ("지민", "jimin"), ("유진", "yujin"), ("현우", "hyunwoo"), ("은서", "eunseo"),
         ("채원", "chaewon"), ("준서", "junseo"), ("다은", "daeun"), ("태윤", "taeyoon"), ("수빈", "subin"),
         ("민서", "minseo"), ("건우", "gunwoo"), ("소율", "soyul"), ("우진", "woojin"), ("나연", "nayeon"),
         ("성민", "sungmin"), ("예린", "yerin"), ("동현", "donghyun"), ("하린", "harin"), ("재원", "jaewon")]
MAIL_DOMAINS = ["example.com", "example.net", "example.org"]

# Hour-of-day traffic shape (KST): lunch bump, evening peak, empty small hours.
HOURLY = [0.9, 0.5, 0.3, 0.2, 0.2, 0.3, 0.6, 1.2, 2.0, 2.4, 2.6, 2.8,
          3.4, 3.1, 2.7, 2.6, 2.8, 3.2, 3.8, 4.6, 5.2, 5.4, 4.4, 2.6]
WEEKDAY = [0.95, 0.95, 1.0, 1.0, 1.05, 1.15, 1.2]  # Mon..Sun


def parse_args():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--users", type=int, default=5_000, help="member count; everything else scales from it")
    p.add_argument("--seed", type=int, default=20261019)
    p.add_argument("--days", type=int, default=90)
    p.add_argument("--dry-run", action="store_true", help="generate and print counts without loading")
    return p.parse_args()


class Seeder:
    def __init__(self, args):
        self.r = random.Random(args.seed)
        self.n_users = args.users
        self.scale = args.users / FULL_USERS
        self.days = args.days
        self.now = datetime.now(KST).replace(second=0, microsecond=0, tzinfo=None)
        self.start = (self.now - timedelta(days=self.days)).replace(hour=0, minute=0)
        self.dry_run = args.dry_run
        self.rows = {}           # table -> list[dict]
        self.event_seq = 0
        self.event_buffer = []
        self.event_total = 0
        self.ids = {}
        self._day_cdf = self._build_day_cdf()
        self._hour_cdf = self._cdf(HOURLY)

    # ── helpers ─────────────────────────────────────────────────────────────────────────
    @staticmethod
    def _cdf(weights):
        total, acc, out = sum(weights), 0.0, []
        for w in weights:
            acc += w / total
            out.append(acc)
        return out

    def _pick(self, cdf):
        return min(bisect.bisect_left(cdf, self.r.random()), len(cdf) - 1)

    def _build_day_cdf(self):
        weights = []
        for d in range(self.days):
            day = self.start + timedelta(days=d)
            trend = 0.85 + 0.3 * d / self.days                      # steady growth over the quarter
            payday = 1.15 if day.day in (24, 25, 26) else 1.0
            weights.append(trend * WEEKDAY[day.weekday()] * payday)
        return self._cdf(weights)

    def random_time(self, not_after=None):
        while True:
            day = self.start + timedelta(days=self._pick(self._day_cdf))
            t = day + timedelta(hours=self._pick(self._hour_cdf), minutes=self.r.randrange(60),
                                seconds=self.r.randrange(60))
            if t <= (not_after or self.now):
                return t

    def time_in_hours(self, day, hour_from=8, hour_to=24):
        hours = HOURLY[hour_from:hour_to]
        h = hour_from + self._pick(self._cdf(hours))
        return day.replace(hour=0, minute=0) + timedelta(hours=h, minutes=self.r.randrange(60),
                                                         seconds=self.r.randrange(60))

    def next_id(self, table):
        self.ids[table] = self.ids.get(table, 0) + 1
        return self.ids[table]

    def add(self, table, row):
        row.setdefault("id", self.next_id(table)) if table not in ("user_summary",) else None
        self.rows.setdefault(table, []).append(row)
        return row

    def scaled(self, full_value, minimum=1):
        return max(minimum, round(full_value * self.scale))

    # ── behavior events (streamed to Elasticsearch) ─────────────────────────────────────
    def event(self, trigger, at, user_id, session_id, page_url, **props):
        self.event_seq += 1
        doc = {
            "eventId": self.event_seq,
            "eventName": trigger,
            "triggerType": trigger,
            "occurredAt": (at.replace(tzinfo=KST)).astimezone(timezone.utc).isoformat(timespec="milliseconds")
                          .replace("+00:00", "Z"),
            "userId": user_id,
            "sessionId": session_id,
            "pageUrl": page_url,
            "referrer": None,
            "userAgent": "Mozilla/5.0 (demo-seed)",
            "properties": {k: v for k, v in props.items() if v is not None} or None,
        }
        self.event_buffer.append(doc)
        self.event_total += 1
        if len(self.event_buffer) >= 5_000:
            self.flush_events()

    def flush_events(self):
        if not self.event_buffer:
            return
        if not self.dry_run:
            lines = []
            for doc in self.event_buffer:
                lines.append('{"index":{}}')
                lines.append(json.dumps(doc, ensure_ascii=False))
            body = ("\n".join(lines) + "\n").encode()
            req = urllib.request.Request(f"{ES_URL}/{ES_INDEX}/_bulk", data=body,
                                         headers={"Content-Type": "application/x-ndjson"})
            with urllib.request.urlopen(req) as resp:
                result = json.loads(resp.read())
            if result.get("errors"):
                first = next(i for i in result["items"] if "error" in i["index"])
                sys.exit(f"Elasticsearch bulk error: {first['index']['error']}")
        self.event_buffer = []

    # ── 1. catalog, members ─────────────────────────────────────────────────────────────
    def build_products(self):
        self.products = []
        for category, (brands, items) in CATEGORIES.items():
            target = round(300 * CATEGORY_WEIGHTS[category])
            combos = [(b, it, v) for b in brands for it in items for v in VARIANTS]
            self.r.shuffle(combos)
            for brand, (item, base), variant in combos[:target]:
                price = round(base * self.r.uniform(0.85, 1.25), -2)
                self.products.append(self.add("products", {
                    "product_name": f"{brand} {item}{variant}", "brand": brand, "category": category,
                    "price": price, "stock": self.r.randrange(200, 5000), "discount_rate": self.r.choice([0, 0, 5, 10, 15]),
                    "campaign_only": 0, "image_url": None}))
        # Popularity is heavily skewed: the top fifth of the catalog takes most of the views.
        self.r.shuffle(self.products)
        self._product_cdf = self._cdf([1 / (rank + 1) ** 1.1 for rank in range(len(self.products))])

    def popular_product(self):
        return self.products[self._pick(self._product_cdf)]

    def build_users(self):
        surname_cdf = self._cdf([s[2] for s in SURNAMES])
        used_emails = set()
        self.users = []
        for _ in range(self.n_users):
            sur, sur_en, _w = SURNAMES[self._pick(surname_cdf)]
            given, given_en = self.r.choice(GIVEN)
            while True:
                email = f"{given_en}.{sur_en}{self.r.randrange(10, 9999)}@{self.r.choice(MAIL_DOMAINS)}"
                if email not in used_emails:
                    used_emails.add(email)
                    break
            signup = self.now - timedelta(days=int(self.r.triangular(1, 900, 120)), minutes=self.r.randrange(1440))
            user = self.add("users", {
                "name": sur + given, "email": email, "role": "USER", "grade": "BRONZE",
                "age": int(self.r.triangular(18, 62, 31)), "provider": "demo", "provider_id": None,
                "picture": None, "created_at": signup, "updated_at": signup})
            # Activity propensity: log-normal so a few heavy shoppers and many occasional visitors.
            user["_rate"] = self.r.lognormvariate(1.6, 0.9) if self.r.random() > 0.28 else 0.0
            user["_last_seen"] = None
            self.users.append(user)

    # ── 2. organic browsing and shop purchases ─────────────────────────────────────────
    def build_organic_sessions(self):
        for user in self.users:
            sessions = self._poisson(user["_rate"])
            for _ in range(sessions):
                at = self.random_time()
                if at < user["created_at"]:
                    continue
                self.session(user, at)

    def _poisson(self, lam):
        if lam <= 0:
            return 0
        l, k, p = math.exp(-lam), 0, 1.0
        while True:
            p *= self.r.random()
            if p <= l:
                return k
            k += 1

    def session(self, user, at, logged_in=None, force_product=None, campaign=None):
        logged_in = self.r.random() < 0.78 if logged_in is None else logged_in
        uid = user["id"] if logged_in else None
        sid = f"s{self.event_seq:x}{self.r.randrange(1 << 20):x}"
        if logged_in:
            user["_last_seen"] = max(user["_last_seen"] or at, at)
        if campaign and campaign["_activities"]:
            activity = self.r.choice(campaign["_activities"])
            self.event("PAGE_VIEW", at, uid, sid, f"/campaign-activities/{activity['id']}",
                       activityId=activity["id"], campaignId=campaign["id"])
            at += timedelta(seconds=self.r.randrange(15, 90))
        viewed = None
        for _ in range(1 + self._geometric(0.45)):
            product = force_product or self.popular_product()
            force_product = None
            viewed = product
            self.event("PAGE_VIEW", at, uid, sid, f"/products/{product['id']}", productId=product["id"])
            if self.r.random() < 0.55:
                self.event("SCROLL", at + timedelta(seconds=8), uid, sid, f"/products/{product['id']}",
                           productId=product["id"], depth=self.r.choice([25, 50, 50, 75, 75, 90, 100]))
            if self.r.random() < 0.5:
                self.event("STAY", at + timedelta(seconds=20), uid, sid, f"/products/{product['id']}",
                           productId=product["id"], durationSec=int(min(600, self.r.lognormvariate(3.4, 0.8))))
            at += timedelta(seconds=self.r.randrange(25, 160))
        if not logged_in or self.r.random() > 0.22:
            return None
        self.event("CART_ITEM_ADDED", at, uid, sid, f"/products/{viewed['id']}", productId=viewed["id"])
        if self.r.random() > 0.6:
            return None
        return self.shop_purchase(user, viewed, at + timedelta(minutes=self.r.randrange(1, 12)), sid)

    def _geometric(self, p):
        k = 0
        while self.r.random() > p and k < 12:
            k += 1
        return k

    def shop_purchase(self, user, product, at, sid, user_coupon=None, discount=0):
        if at > self.now:
            return None
        price = max(1000, round(product["price"] * (1 - product["discount_rate"] / 100) - discount, -2))
        cancelled = self.r.random() < 0.035
        purchase = self.add("purchases", {
            "user_id": user["id"], "product_id": product["id"], "campaign_activity_id": None,
            "purchase_type": "SHOP", "price": price, "quantity": 1 if self.r.random() < 0.9 else 2,
            "purchase_at": at, "status": "CANCELLED" if cancelled else "CONFIRMED",
            "cancelled_at": at + timedelta(hours=self.r.randrange(2, 72)) if cancelled else None,
            "cancellation_reason": "고객 변심" if cancelled else None,
            "user_coupon_id": user_coupon["id"] if user_coupon else None})
        if user_coupon:
            user_coupon["status"], user_coupon["used_at"] = "USED", at
        self.event("PURCHASE", at, user["id"], sid, "/payment/success", productId=product["id"])
        return purchase

    # ── 3. campaigns ────────────────────────────────────────────────────────────────────
    def day(self, offset):
        return (self.now + timedelta(days=offset)).replace(hour=0, minute=0)

    def build_campaigns(self):
        plan = [
            ("추석 선물 기획전", -39, -23, 3.1, [
                ("FCFS", "한우 선물세트 선착순 특가", -28, 500, ("FOOD", "한우 1++ 선물세트", 189_000), 129_000),
                ("COUPON", "추석 선물 15% 쿠폰", -36, 0.125, 15)]),
            ("가을 신상 세일", -21, -12, 0.7, [
                ("COUPON", "가을 니트 20% 쿠폰", -21, 0.083, 20),
                ("COUPON", "신규 회원 1만원 쿠폰", -18, 0.082, 10_000)]),
            ("가을 테크 위크", -6, 1, 4.2, [
                ("FCFS", "스마트워치 울트라 선착순", -2, 800, ("DIGITAL", "노바텍 스마트워치 울트라", 329_000), 199_000),
                ("FCFS", "무선 이어폰 프로 선착순", -1, 1500, ("DIGITAL", "루멘 무선 이어폰 프로", 179_000), 89_000),
                ("COUPON", "디지털 액세서리 10% 쿠폰", -6, 0.13, 10)]),
            ("겨울 아우터 얼리버드", -14, 10, 2.6, [
                ("FCFS", "롱패딩 얼리버드 선착순", -10, 300, ("FASHION", "노스폴 구스 롱패딩", 289_000), 149_000),
                ("GIVEAWAY", "패딩 1+1 추첨 이벤트", -12, 120, None, 0)]),
            ("블랙프라이데이", 34, 41, None, [
                ("COUPON", "블랙프라이데이 사전 쿠폰", 34, 0.0, 15)]),
        ]
        # Audience segments let a coupon activity prepare a new dispatch run from the console.
        segments = {rfm: self.add("audience_segments", {"name": label, "target_rfm_segment": rfm, "is_active": 1,
                                                        "created_at": self.day(-120), "updated_at": self.day(-120)})
                    for rfm, label in [("VIP", "VIP 고객"), ("LOYAL", "충성 고객"),
                                       ("AT_RISK", "이탈 우려 고객"), ("DORMANT", "휴면 고객")]}
        campaign_segments = {"가을 테크 위크": "LOYAL", "블랙프라이데이": "AT_RISK"}
        self.campaigns = []
        for name, s, e, target_roas, activities in plan:
            segment = segments.get(campaign_segments.get(name))
            campaign = self.add("campaigns", {
                "name": name, "start_at": self.day(s).replace(hour=10), "end_at": self.day(e).replace(hour=23, minute=59),
                "budget": 0, "reward_type": None, "reward_payload": None,
                "target_segment_id": segment["id"] if segment else None,
                "created_at": self.day(min(s, 0) - 7), "updated_at": self.day(min(s, 0) - 7)})
            campaign["_activities"], campaign["_start"], campaign["_end"] = [], s, e
            campaign["_target_roas"] = target_roas
            self.campaigns.append(campaign)
            for spec in activities:
                self.build_activity(campaign, spec)

    def activity_status(self, campaign):
        if campaign["_start"] > 0:
            return "DRAFT"
        return "ENDED" if campaign["_end"] < 0 else "ACTIVE"

    # Unsplash photos (free license) for the FCFS products that appear in screenshots.
    FCFS_IMAGES = {"한우 1++ 선물세트": "/image/product/fcfs_hanwoo.jpg",
                   "노바텍 스마트워치 울트라": "/image/product/fcfs_smartwatch.jpg",
                   "루멘 무선 이어폰 프로": "/image/product/fcfs_earbuds.jpg",
                   "노스폴 구스 롱패딩": "/image/product/fcfs_padding.jpg"}

    def build_activity(self, campaign, spec):
        kind = spec[0]
        status = self.activity_status(campaign)
        base = {"campaign_id": campaign["id"], "status": status, "filters": None, "image_url": None,
                "synced_count": 0, "start_date": campaign["start_at"], "end_date": campaign["end_at"],
                "created_at": campaign["created_at"], "updated_at": campaign["created_at"],
                "coupon_id": None, "product_id": None, "marketing_action_id": None,
                "expected_recipient_count": None, "max_recipient_count": None, "operator_memo": None,
                "purpose": None, "holdout_rate_percent": None}
        if kind == "FCFS":
            _, name, open_offset, limit, (category, product_name, list_price), price = spec
            product = self.add("products", {"product_name": product_name, "brand": product_name.split()[0],
                                            "category": category, "price": list_price, "stock": limit,
                                            "discount_rate": 0, "campaign_only": 1,
                                            "image_url": self.FCFS_IMAGES.get(product_name)})
            limit_s = max(40, round(limit * math.sqrt(self.scale)))
            activity = self.add("campaign_activities", {**base, "name": name, "activity_type": "FIRST_COME_FIRST_SERVE",
                                                        "product_id": product["id"], "limit_count": limit_s,
                                                        "image_url": product["image_url"],
                                                        "price": price, "quantity": limit_s,
                                                        "budget": 0})
            activity["_open"] = self.day(open_offset).replace(hour=20)
            activity["_product"] = product
        elif kind == "COUPON":
            _, name, dispatch_offset, treatment_rate, discount = spec
            coupon = self.add("coupons", {
                "coupon_name": name.replace(" 쿠폰", ""), "discount_rate": discount if discount < 100 else None,
                "discount_amount": discount if discount >= 100 else None, "min_order_amount": 30_000,
                "target_category": None, "start_date": self.day(dispatch_offset), "end_date": self.day(dispatch_offset + 14)})
            action = self.add("marketing_actions", {"action_type": "COUPON", "is_active": 1, "reference_id": coupon["id"],
                                                    "marketing_rule_id": None, "created_at": campaign["created_at"],
                                                    "updated_at": campaign["created_at"]})
            activity = self.add("campaign_activities", {**base, "name": name, "activity_type": "COUPON",
                                                        "coupon_id": coupon["id"], "marketing_action_id": action["id"],
                                                        "limit_count": 20_000, "price": 0, "quantity": 0,
                                                        "budget": 0, "coupon_delivery_mode": "DIRECT",
                                                        "holdout_rate_percent": 10, "purpose": "GENERAL_PROMOTION"})
            activity.update(_coupon=coupon, _action=action, _dispatch=self.day(dispatch_offset).replace(hour=10),
                            _treatment_rate=treatment_rate, _discount=discount)
        else:  # GIVEAWAY: applications only; this activity type has no funnel definition yet
            _, name, open_offset, winners, _p, _price = spec
            activity = self.add("campaign_activities", {**base, "name": name, "activity_type": "GIVEAWAY",
                                                        "limit_count": max(10, round(winners * math.sqrt(self.scale))),
                                                        "price": 0, "quantity": 0, "budget": 0})
            activity["_open"] = self.day(open_offset).replace(hour=10)
        activity["_kind"] = kind
        campaign["_activities"].append(activity)

    def run_campaign_traffic(self):
        """Landing-page visits during each campaign window, mixed into ordinary sessions."""
        active_users = [u for u in self.users if u["_rate"] > 0]
        for campaign in self.campaigns:
            if campaign["_start"] > 0:
                continue
            days = range(max(campaign["_start"], -self.days), min(campaign["_end"], 0) + 1)
            per_day = self.scaled(2_400)
            for d in days:
                for _ in range(per_day):
                    user = self.r.choice(active_users)
                    self.session(user, self.time_in_hours(self.day(d), 0, 24), campaign=campaign)

    def run_fcfs(self, campaign, activity):
        """Open at 20:00: a burst of clicks within seconds, sold out, a few unpaid slots re-sold later."""
        if activity["_open"] > self.now:
            return
        limit = activity["limit_count"]
        attempts = max(limit * 3, self.scaled(12 * activity["limit_count"] / math.sqrt(self.scale)))
        crowd = self.r.sample([u for u in self.users if u["_rate"] > 0], min(attempts, len(self.users) // 2))
        open_at, page = activity["_open"], f"/campaign-activities/{activity['id']}"
        clicks = []
        for user in crowd:
            sid = f"f{activity['id']}-{user['id']}"
            wait = timedelta(seconds=self.r.randrange(30, 900))
            self.event("PAGE_VIEW", open_at - wait, user["id"], sid, page, activityId=activity["id"],
                       campaignId=campaign["id"])
            click_at = open_at + timedelta(seconds=self.r.expovariate(1 / 2.5))
            self.event("CLICK", click_at, user["id"], sid, page, activityId=activity["id"], campaignId=campaign["id"])
            clicks.append((click_at, user, sid))
        clicks.sort(key=lambda c: c[0])
        # The first `limit` clicks win; a few later clicks pick up slots that unpaid winners released.
        queue = [(c, False) for c in clicks[:limit]] + [(c, True) for c in clicks[limit:limit + round(limit * 0.06)]]
        paid = 0
        for (click_at, user, sid), refilled in queue:
            if paid >= limit:
                break
            approved_at = click_at + timedelta(milliseconds=self.r.randrange(30, 120))
            if refilled:
                approved_at = open_at + timedelta(minutes=self.r.randrange(5, 15))
            self.event("APPROVED", approved_at, user["id"], sid, page, activityId=activity["id"],
                       campaignId=campaign["id"])
            if not refilled and self.r.random() < 0.075:
                continue  # never paid; the 5-minute token expired and the slot went back to stock
            pay_at = approved_at + timedelta(seconds=self.r.randrange(25, 240))
            if pay_at > self.now:
                continue
            paid += 1
            self.add("campaign_activity_entries", {
                "campaign_activity_id": activity["id"], "product_id": activity["product_id"], "status": "APPROVED",
                "user_id": user["id"], "requested_at": click_at, "processed_at": pay_at, "additional_data": None,
                "created_at": pay_at, "updated_at": pay_at})
            self.add("purchases", {
                "user_id": user["id"], "product_id": activity["product_id"], "campaign_activity_id": activity["id"],
                "purchase_type": "CAMPAIGNACTIVITY", "price": activity["price"], "quantity": 1, "purchase_at": pay_at,
                "status": "CONFIRMED", "cancelled_at": None, "cancellation_reason": None, "user_coupon_id": None})
            self.event("PURCHASE", pay_at, user["id"], sid, "/payment/success", activityId=activity["id"],
                       campaignId=campaign["id"], productId=activity["product_id"])
            user["_last_seen"] = max(user["_last_seen"] or pay_at, pay_at)
        activity["_paid"] = paid

    def run_giveaway(self, campaign, activity):
        if activity["_open"] > self.now:
            return
        for user in self.r.sample(self.users, min(len(self.users) // 3, self.scaled(9_000))):
            at = activity["_open"] + timedelta(hours=self.r.uniform(0, 72))
            if at > self.now:
                continue
            self.add("campaign_activity_entries", {
                "campaign_activity_id": activity["id"], "product_id": None, "status": "PENDING",
                "user_id": user["id"], "requested_at": at, "processed_at": None, "additional_data": None,
                "created_at": at, "updated_at": at})

    def run_coupon(self, campaign, activity):
        """Coupon run with a 10% holdout; purchases over the following two weeks."""
        if activity["_dispatch"] > self.now:
            return
        dispatch = activity["_dispatch"]
        pool = [u for u in self.users if u["created_at"] < dispatch]
        # At least 1,500 targets so the 10% holdout is not too small to compare at trial scale.
        targets = self.r.sample(pool, min(len(pool) // 2, max(1_500, self.scaled(11_000))))
        holdout_n = len(targets) // 10
        run = self.add("campaign_activity_runs", {
            "campaign_activity_id": activity["id"], "status": "DISPATCHED", "target_count": len(targets),
            "treatment_count": len(targets) - holdout_n, "holdout_count": holdout_n, "dispatched_at": dispatch,
            "fact_snapshot_json": json.dumps({"targetCount": len(targets), "holdoutRatePercent": 10}),
            "decided_at": dispatch - timedelta(minutes=20), "decided_by": "operator",
            "created_at": dispatch - timedelta(hours=1), "updated_at": dispatch})
        holdout_rate = 0.08
        for i, user in enumerate(targets):
            is_holdout = i < holdout_n
            execution_id = None
            user_coupon = None
            if not is_holdout:
                execution = self.add("marketing_action_executions", {
                    "marketing_action_id": activity["_action"]["id"], "action_reference_id": activity["_coupon"]["id"],
                    "user_id": user["id"], "product_id": None, "marketing_rule_id": None,
                    "campaign_activity_run_id": run["id"], "reengagement_campaign_id": None, "channel": "COUPON",
                    "created_at": dispatch, "updated_at": dispatch})
                execution_id = execution["id"]
                user_coupon = self.add("user_coupons", {
                    "user_id": user["id"], "coupon_id": activity["_coupon"]["id"], "status": "ISSUED", "used_at": None,
                    "marketing_action_execution_id": execution_id, "created_at": dispatch, "updated_at": dispatch})
            self.add("campaign_activity_run_targets", {
                "run_id": run["id"], "user_id": user["id"], "execution_id": execution_id,
                "assignment_group": "HOLDOUT" if is_holdout else "TREATMENT", "baseline_rfm_segment": None})
            rate = holdout_rate if is_holdout else activity["_treatment_rate"]
            if self.r.random() >= rate:
                continue
            at = dispatch + timedelta(hours=self.r.uniform(1, 14 * 24))
            product = self.popular_product()
            sid = f"c{run['id']}-{user['id']}"
            self.event("PAGE_VIEW", at - timedelta(minutes=6), user["id"], sid, f"/products/{product['id']}",
                       productId=product["id"])
            use_coupon = user_coupon is not None and self.r.random() < 0.85
            discount = 0
            if use_coupon:
                d = activity["_discount"]
                discount = d if d >= 100 else round(product["price"] * d / 100, -2)
            self.shop_purchase(user, product, at, sid, user_coupon if use_coupon else None, discount)

    def run_automated_rules(self):
        """Behavior-rule coupons outside campaigns (e.g. three product views in a week)."""
        rules = [("상품 3회 조회 쿠폰", "PAGE_VIEW", 3, 7, 5_000), ("장바구니 재방문 쿠폰", "CART_ITEM_ADDED", 1, 3, 3_000)]
        active = [u for u in self.users if u["_rate"] > 0]
        for name, behavior, threshold, lookback, discount in rules:
            created = self.start - timedelta(days=20)
            rule = self.add("marketing_rules", {"rule_name": name, "behavior_type": behavior, "threshold_count": threshold,
                                                "lookback_days": lookback, "dedup_ttl_days": 30, "is_active": 1,
                                                "property_conditions": None, "target_product_id": None,
                                                "audience_segment_id": None, "created_at": created, "updated_at": created})
            coupon = self.add("coupons", {"coupon_name": name.replace(" 쿠폰", ""), "discount_rate": None,
                                          "discount_amount": discount, "min_order_amount": 20_000, "target_category": None,
                                          "start_date": self.start, "end_date": self.now + timedelta(days=30)})
            action = self.add("marketing_actions", {"action_type": "COUPON", "is_active": 1, "reference_id": coupon["id"],
                                                    "marketing_rule_id": rule["id"], "created_at": created,
                                                    "updated_at": created})
            for user in self.r.sample(active, min(len(active) // 4, self.scaled(7_000))):
                at = self.random_time()
                execution = self.add("marketing_action_executions", {
                    "marketing_action_id": action["id"], "action_reference_id": coupon["id"], "user_id": user["id"],
                    "product_id": None, "marketing_rule_id": rule["id"], "campaign_activity_run_id": None,
                    "reengagement_campaign_id": None, "channel": "COUPON", "created_at": at, "updated_at": at})
                user_coupon = self.add("user_coupons", {
                    "user_id": user["id"], "coupon_id": coupon["id"], "status": "ISSUED", "used_at": None,
                    "marketing_action_execution_id": execution["id"], "created_at": at, "updated_at": at})
                if self.r.random() < 0.16:
                    product = self.popular_product()
                    self.shop_purchase(user, product, at + timedelta(hours=self.r.uniform(1, 96)),
                                       f"r{execution['id']}", user_coupon, discount)

    def set_budgets(self):
        """Budgets follow from a target ROAS over the dashboard's 30-day window, so ratios stay
        realistic at any --users scale. Campaigns without revenue yet get a planned budget."""
        since = self.now - timedelta(days=30)
        run_activity = {r["id"]: r["campaign_activity_id"] for r in self.rows.get("campaign_activity_runs", [])}
        execution_run = {e["id"]: e["campaign_activity_run_id"] for e in self.rows.get("marketing_action_executions", [])}
        coupon_execution = {c["id"]: c["marketing_action_execution_id"] for c in self.rows.get("user_coupons", [])}
        revenue = {}
        for p in self.rows.get("purchases", []):
            if p["status"] != "CONFIRMED" or p["purchase_at"] < since:
                continue
            activity_id = p["campaign_activity_id"]
            if activity_id is None and p["user_coupon_id"]:
                activity_id = run_activity.get(execution_run.get(coupon_execution.get(p["user_coupon_id"])))
            if activity_id:
                revenue[activity_id] = revenue.get(activity_id, 0) + p["price"] * p["quantity"]
        planned = []
        for campaign in self.campaigns:
            if not campaign["_target_roas"]:
                continue
            total = 0
            for a in campaign["_activities"]:
                a["budget"] = round(revenue.get(a["id"], 0) / campaign["_target_roas"], -4)
                total += a["budget"]
            campaign["budget"] = round(total, -5) or 1_000_000
            planned.append(campaign["budget"])
        for campaign in self.campaigns:
            if not campaign["_target_roas"]:
                campaign["budget"] = round(max(planned) * 1.2, -5)

    # ── 4. customer summary (RFM) ───────────────────────────────────────────────────────
    def build_summaries(self):
        stats = {}
        for p in self.rows.get("purchases", []):
            if p["status"] != "CONFIRMED":
                continue
            s = stats.setdefault(p["user_id"], [None, 0, 0])
            s[0] = max(s[0] or p["purchase_at"], p["purchase_at"])
            s[1] += 1
            s[2] += p["price"] * p["quantity"]
        monetary = sorted(s[2] for s in stats.values())
        vip_cut = monetary[int(len(monetary) * 0.85)] if monetary else 0
        grades = {"VIP": "DIAMOND", "LOYAL": "GOLD", "AT_RISK": "SILVER", "DORMANT": "BRONZE"}
        for user in self.users:
            last, freq, money = stats.get(user["id"], (None, 0, 0))
            recency = (self.now - last).days if last else None
            if last is None:
                segment = "DORMANT" if (self.now - user["created_at"]).days > 60 else None
            elif money >= vip_cut and recency <= 30:
                segment = "VIP"
            elif freq >= 3 and recency <= 45:
                segment = "LOYAL"
            elif recency > 30:
                segment = "AT_RISK"
            else:
                segment = None
            user["grade"] = grades.get(segment, "BRONZE")
            self.add("user_summary", {"user_id": user["id"], "last_purchase_at": last,
                                      "last_login_at": user["_last_seen"], "rfm_segment": segment})

    # ── load ────────────────────────────────────────────────────────────────────────────
    TABLES = ["users", "user_summary", "products", "audience_segments", "campaigns", "coupons", "marketing_rules", "marketing_actions",
              "campaign_activities", "campaign_activity_entries", "campaign_activity_runs",
              "marketing_action_executions", "campaign_activity_run_targets", "user_coupons", "purchases"]
    # Tables that reference the ones above; emptied so nothing points at old ids.
    DEPENDENTS = ["marketing_action_dispatches", "marketing_action_triage_cases", "cohort_ltv_monthly_stats",
                  "abandoned_cart_coupon_candidates", "abandoned_cart_coupon_campaigns", "dormant_customer_campaigns"]

    def mysql(self, sql, capture=False):
        cmd = ["docker", "exec", "-i", MYSQL_CONTAINER, "sh", "-c",
               'mysql -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" axon_db --default-character-set=utf8mb4 -N 2>/dev/null']
        res = subprocess.run(cmd, input=sql.encode(), capture_output=True)
        if res.returncode != 0:
            sys.exit(f"MySQL error: {res.stderr.decode()[:500]}")
        return res.stdout.decode() if capture else None

    @staticmethod
    def literal(v):
        if v is None:
            return "NULL"
        if isinstance(v, bool):
            return "1" if v else "0"
        if isinstance(v, (int, float)):
            return repr(v)
        if isinstance(v, datetime):
            return "'" + v.strftime("%Y-%m-%d %H:%M:%S") + "'"
        return "'" + str(v).replace("\\", "\\\\").replace("'", "\\'") + "'"

    def load_mysql(self):
        # Prerequisite from scripts/migrations/2026-09-30-add-campaign-activity-run-review.sql
        self.mysql("ALTER TABLE marketing_actions MODIFY COLUMN marketing_rule_id BIGINT NULL;"
                   "ALTER TABLE marketing_action_executions MODIFY COLUMN marketing_rule_id BIGINT NULL;")
        existing = set(self.mysql("SHOW TABLES;", capture=True).split())
        columns = {}
        for line in self.mysql("SELECT table_name, column_name FROM information_schema.columns "
                               "WHERE table_schema='axon_db';", capture=True).splitlines():
            t, c = line.split("\t")
            columns.setdefault(t, set()).add(c)
        truncate = [t for t in self.DEPENDENTS + self.TABLES if t in existing]
        self.mysql("SET FOREIGN_KEY_CHECKS=0;" + "".join(f"TRUNCATE TABLE {t};" for t in truncate) +
                   "SET FOREIGN_KEY_CHECKS=1;")
        for table in self.TABLES:
            rows = self.rows.get(table, [])
            if not rows:
                continue
            cols = [c for c in rows[0] if not c.startswith("_")]
            unknown = [c for c in cols if c not in columns.get(table, set())]
            if unknown:
                sys.exit(f"{table}: columns not in the database schema: {unknown}")
            statements = ["SET FOREIGN_KEY_CHECKS=0;"]
            for i in range(0, len(rows), 1000):
                values = ",".join("(" + ",".join(self.literal(r.get(c)) for c in cols) + ")" for r in rows[i:i + 1000])
                statements.append(f"INSERT INTO {table} ({','.join(cols)}) VALUES {values};")
            statements.append("SET FOREIGN_KEY_CHECKS=1;")
            self.mysql("\n".join(statements))
            print(f"  mysql {table:32s} {len(rows):>9,}")

    def reset_elasticsearch(self):
        req = urllib.request.Request(f"{ES_URL}/{ES_INDEX}", method="DELETE")
        try:
            urllib.request.urlopen(req)
        except urllib.error.HTTPError as e:
            if e.code != 404:
                raise

    def load_redis(self):
        """Live FCFS counters so the realtime dashboard shows remaining stock for active activities."""
        commands = []
        for campaign in self.campaigns:
            for a in campaign["_activities"]:
                if a["_kind"] != "FCFS":
                    continue
                commands.append(f"DEL campaign:{a['id']}:counter campaign:{a['id']}:users")
                if a["status"] == "ACTIVE" and "_paid" in a:
                    commands.append(f"SET campaign:{a['id']}:counter {a['_paid']}")
        script = "\n".join(commands) + "\n"
        subprocess.run(["docker", "exec", "-i", REDIS_CONTAINER, "sh", "-c",
                        'redis-cli -a "${REDIS_PASSWORD:-axon1234}" --no-auth-warning >/dev/null'],
                       input=script.encode(), check=True)

    def verify(self):
        checks = self.mysql("""
            SELECT 'fcfs entries over limit', COUNT(*) FROM (
              SELECT a.id FROM campaign_activities a JOIN campaign_activity_entries e ON e.campaign_activity_id = a.id
              WHERE a.activity_type = 'FIRST_COME_FIRST_SERVE' GROUP BY a.id, a.limit_count
              HAVING COUNT(*) > a.limit_count) x;
            SELECT 'coupons used twice', COUNT(*) FROM (
              SELECT user_coupon_id FROM purchases WHERE user_coupon_id IS NOT NULL
              GROUP BY user_coupon_id HAVING COUNT(*) > 1) x;
            SELECT 'used coupons without purchase', COUNT(*) FROM user_coupons uc
              WHERE uc.status = 'USED' AND NOT EXISTS (SELECT 1 FROM purchases p WHERE p.user_coupon_id = uc.id);
            SELECT 'fcfs purchases without entry', COUNT(*) FROM purchases p WHERE p.purchase_type = 'CAMPAIGNACTIVITY'
              AND NOT EXISTS (SELECT 1 FROM campaign_activity_entries e
                              WHERE e.campaign_activity_id = p.campaign_activity_id AND e.user_id = p.user_id);
            """, capture=True)
        failed = False
        for line in checks.strip().splitlines():
            name, count = line.split("\t")
            print(f"  check {name:32s} {count}")
            failed |= count != "0"
        if failed:
            sys.exit("integrity check failed")

    # ── main ────────────────────────────────────────────────────────────────────────────
    def run(self):
        print(f"seeding {self.n_users:,} users, window {self.start:%Y-%m-%d} ~ {self.now:%Y-%m-%d %H:%M} KST")
        if not self.dry_run:
            self.reset_elasticsearch()
        self.build_products()
        self.build_users()
        self.build_campaigns()
        self.build_organic_sessions()
        self.run_campaign_traffic()
        for campaign in self.campaigns:
            for activity in campaign["_activities"]:
                {"FCFS": self.run_fcfs, "COUPON": self.run_coupon, "GIVEAWAY": self.run_giveaway}[activity["_kind"]](
                    campaign, activity)
        self.run_automated_rules()
        self.set_budgets()
        self.build_summaries()
        self.flush_events()
        print(f"  elasticsearch behavior events   {self.event_total:>9,}")
        if self.dry_run:
            for table in self.TABLES:
                print(f"  (dry) {table:32s} {len(self.rows.get(table, [])):>9,}")
            return
        self.load_mysql()
        self.load_redis()
        urllib.request.urlopen(urllib.request.Request(f"{ES_URL}/{ES_INDEX}/_refresh", method="POST"))
        self.verify()
        print("done")


if __name__ == "__main__":
    Seeder(parse_args()).run()
