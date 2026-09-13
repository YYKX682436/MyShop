import assert from "node:assert/strict"
import test from "node:test"
import { readFileSync } from "node:fs"

const readSource = (relativePath) => readFileSync(new URL(relativePath, import.meta.url), "utf8")

test("account home owns the complete logged-in summary and scoped account actions", () => {
  const source = readSource("../app/(store)/my/page.tsx")
  const avatarSource = readSource("../components/store/account-avatar.tsx")
  const dictionary = readSource("./i18n.ts")
  const loadingSource = source.slice(source.indexOf("function LoadingAccount"), source.indexOf("export default function MyPage"))
  const identityBadgeStart = source.indexOf("const identityBadge =")
  const metadataRowStart = source.indexOf('className="mt-2 flex min-w-0 max-w-full items-center gap-2"', identityBadgeStart)
  const metadataRowEnd = source.indexOf("</div>", metadataRowStart) + "</div>".length
  const metadataRow = source.slice(metadataRowStart, metadataRowEnd)

  for (const route of ["/my/orders", "/my/security", "/my/settings"]) {
    assert.equal(source.includes(`href="${route}"`), true, route)
  }
  assert.match(source, /user\.avatar_url/)
  assert.match(source, /user\.user_no/)
  assert.match(source, /user\.balance/)
  assert.match(source, /user\.user_level_name/)
  assert.match(source, /const copyAccountValue = async/)
  assert.match(source, /copyText\(value\)/)
  assert.match(source, /copyAccountValue\(user\.username, t\("my\.usernameCopied"\)\)/)
  assert.match(source, /aria-label=\{t\("my\.copyUsername"\)\}/)
  assert.match(source, /type="button"[\s\S]*aria-label=\{t\("my\.copyUsername"\)\}[\s\S]*focus-visible:ring-2/)
  assert.ok(metadataRowStart >= 0)
  assert.match(metadataRow, /className="mt-2 flex min-w-0 max-w-full items-center gap-2"/)
  assert.match(metadataRow, /\{identityBadge\}/)
  assert.match(metadataRow, /user\.user_no !== null/)
  assert.doesNotMatch(metadataRow, /justify-between/)
  assert.doesNotMatch(metadataRow, /ml-auto/)
  assert.match(metadataRow, /className="inline-flex shrink-0 items-center/)
  assert.ok(metadataRow.indexOf("{identityBadge}") < metadataRow.indexOf("handleCopyUserId"))
  assert.match(source, /const identityBadge =/)
  assert.match(source, /user\.user_no !== null[\s\S]*handleCopyUserId/)
  assert.match(source, /copyAccountValue\(String\(user\.user_no\), t\("my\.userIdCopied"\)\)/)
  assert.match(source, /formatYuan\(user\.balance\)/)
  assert.match(source, /WalletCards/)
  assert.match(source, /userApi\.uploadAvatar/)
  assert.match(source, /setUser\(updatedProfile\)/)
  assert.doesNotMatch(source, /user\.email/)
  assert.doesNotMatch(source, /\bCopy\b/)
  assert.doesNotMatch(source, /<Copy/)
  assert.doesNotMatch(source, /AccountStat/)
  assert.doesNotMatch(source, /my\.userLevel|my\.role/)
  assert.doesNotMatch(source, /user\.id/)
  assert.doesNotMatch(source, /user\.user_no \?\?|user\.user_no \|\|/)
  assert.match(source, /<Badge[\s\S]*Crown[\s\S]*user\.user_level_name/)
  assert.match(source, /user\.unlimited \? "∞" : formatYuan\(user\.balance\)/)
  assert.equal((dictionary.match(/"my\.copyUsername"/g) || []).length, 2)
  assert.equal((dictionary.match(/"my\.usernameCopied"/g) || []).length, 2)
  assert.match(loadingSource, /<header[\s\S]*aria-hidden="true"/)
  assert.equal((loadingSource.match(/<section/g) || []).length, 2)
  assert.match(loadingSource, /<section className="store-glass rounded-card p-5 sm:p-6"/)
  assert.match(loadingSource, /<section\n\s+className="relative overflow-hidden rounded-card border border-amber-300\/40/)
  const identitySkeletonStart = loadingSource.indexOf('<section className="store-glass')
  const balanceSkeletonStart = loadingSource.indexOf('<section\n        className="relative overflow-hidden rounded-card')
  assert.ok(balanceSkeletonStart > identitySkeletonStart)
  assert.match(loadingSource.slice(identitySkeletonStart, balanceSkeletonStart), /metadata|mt-2 flex min-w-0 max-w-full items-center gap-2/)
  assert.doesNotMatch(loadingSource.slice(identitySkeletonStart, balanceSkeletonStart), /justify-between/)
  assert.doesNotMatch(loadingSource.slice(identitySkeletonStart, balanceSkeletonStart), /h-28/)
  assert.match(loadingSource.slice(identitySkeletonStart), /<\/section>\s*<section/)
  assert.doesNotMatch(loadingSource, /setTimeout/)
  assert.doesNotMatch(source, /\/profile/)
  assert.doesNotMatch(source, /my\.darkMode|my\.english/)
  assert.match(avatarSource, /<button/)
  assert.match(avatarSource, /onLoadingStatusChange/)
  assert.match(avatarSource, /getAvatarDisplayState/)
  for (const removedToken of [
    ["Camera"],
    ["Rotate", "Ccw"],
    ["<", "Button"],
    ["on", "Delete"],
    ["change", "Avatar"],
    ["restore", "Avatar"],
  ]) {
    assert.doesNotMatch(avatarSource, new RegExp(removedToken.join("")))
  }
  for (const removedToken of [
    ["handleAvatar", "Delete"],
    ["delete", "Avatar"],
    ["avatar", "Restored"],
  ]) {
    assert.doesNotMatch(source, new RegExp(removedToken.join("")))
  }
})

test("security page presents the current email before the password form", () => {
  const source = readSource("../app/(store)/my/security/page.tsx")
  const dictionary = readSource("./i18n.ts")

  assert.match(source, /user\.email/)
  assert.match(source, /security\.email/)
  assert.match(source, /security\.emailNotBound/)
  assert.match(source, /Mail/)
  assert.ok(source.indexOf("security.email") < source.indexOf("<ChangePasswordForm"))
  assert.equal((dictionary.match(/"security\.email"/g) || []).length, 2)
  assert.equal((dictionary.match(/"security\.emailNotBound"/g) || []).length, 2)
})

test("security flow clears in-memory auth state and navigates to login after password change", () => {
  const source = readSource("../components/store/change-password-form.tsx")

  assert.match(source, /userApi\.updatePassword/)
  assert.match(source, /setUser\(null\)/)
  assert.match(source, /router\.replace\("\/login"\)/)
  assert.match(source, /security\.passwordChanged/)
})

test("settings flow delegates language and all three theme modes to existing contexts", () => {
  const source = readSource("../app/(store)/my/settings/page.tsx")

  assert.match(source, /useLocale/)
  assert.match(source, /setLocale/)
  assert.match(source, /useTheme/)
  assert.match(source, /setTheme/)
  assert.match(source, /value: "system"/)
  assert.match(source, /settings\.chinese/)
  assert.match(source, /settings\.english/)
})

test("the removed profile page has no storefront route or page implementation", () => {
  assert.equal(readSource("./storefront-routes.ts").includes("/profile"), false)
  assert.throws(() => readFileSync(new URL("../app/(store)/profile/page.tsx", import.meta.url)), /ENOENT/)
})

test("avatar API surface keeps upload and removes default-avatar deletion", () => {
  const source = readSource("../services/api.ts")

  assert.match(source, /uploadAvatar/)
  assert.doesNotMatch(source, new RegExp(["delete", "Avatar"].join("")))
})
